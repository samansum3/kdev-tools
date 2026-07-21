package com.khalibre.tools.devpanel.tickets

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.khalibre.tools.devpanel.config.DevConfig
import com.khalibre.tools.devpanel.tickets.JiraUserService.fetchAndCacheUsers
import java.io.File
import java.net.HttpURLConnection

/**
 * Loads and caches Jira project-assignable users, and performs assignee mutations.
 *
 * Cache file lives under  .git/cw/
 *   jira-users.json — JSON array of { accountId, displayName, emailAddress, avatarUrl } objects
 *
 * Cache is permanent once written, same pattern as [JiraMetaService]. Explicit reload
 * deletes the cache file then calls [fetchAndCacheUsers] again.
 */
object JiraUserService {

    data class JiraUser(
        val accountId: String,
        val displayName: String,
        val emailAddress: String?,
        val avatarUrl: String?
    )

    private val gson = Gson()

    fun usersCacheFile(cwDir: File) = File(cwDir, "jira-users.json")

    /** Disk-cache file for a user's avatar, keyed by accountId. e.g. icons/user-<accountId>.png */
    fun userAvatarCacheFile(cwDir: File, accountId: String): File {
        val safe = accountId.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        return File(File(cwDir, "icons"), "user-$safe.png")
    }

    /** Returns cached assignable users if available, otherwise fetches from Jira and caches. */
    fun loadUsers(cwDir: File): List<JiraUser> {
        val cache = usersCacheFile(cwDir)
        if (cache.exists()) {
            return try {
                JsonParser.parseString(cache.readText()).asJsonArray.map {
                    val obj = it.asJsonObject
                    JiraUser(
                        accountId = obj.get("accountId").asString,
                        displayName = obj.get("displayName").asString,
                        emailAddress = obj.get("emailAddress")
                            ?.takeIf { e -> !e.isJsonNull }?.asString,
                        avatarUrl = obj.get("avatarUrl")?.takeIf { a -> !a.isJsonNull }?.asString
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        return fetchAndCacheUsers(cwDir)
    }

    /** Force-fetches assignable users from Jira, overwrites cache, returns fresh list. */
    fun fetchAndCacheUsers(cwDir: File): List<JiraUser> {
        return try {
            val cfg = DevConfig.load(cwDir)
            val baseUrl = cfg.jira.base_url.trimEnd('/')
            val project = cfg.jira.project_key
            if (baseUrl.isBlank() || project.isBlank()) return emptyList()

            val users = mutableListOf<JiraUser>()
            var startAt = 0
            val pageSize = 50
            while (true) {
                val conn = openJiraConn(
                    cwDir,
                    "$baseUrl/rest/api/3/user/assignable/search?project=$project&startAt=$startAt&maxResults=$pageSize"
                )
                if (conn.responseCode != 200) break
                val arr =
                    JsonParser.parseString(conn.inputStream.bufferedReader().readText()).asJsonArray
                if (arr.size() == 0) break
                arr.forEach { el ->
                    val obj = el.asJsonObject
                    val accountId = obj.get("accountId")?.asString ?: return@forEach
                    val displayName = obj.get("displayName")?.asString ?: accountId
                    val emailAddress = obj.get("emailAddress")?.takeIf { !it.isJsonNull }?.asString
                    val avatarUrl = obj.getAsJsonObject("avatarUrls")
                        ?.get("48x48")?.takeIf { !it.isJsonNull }?.asString
                    users += JiraUser(accountId, displayName, emailAddress, avatarUrl)
                }
                if (arr.size() < pageSize) break
                startAt += pageSize
            }

            val sorted = users.distinctBy { it.accountId }.sortedBy { it.displayName.lowercase() }
            cwDir.mkdirs()
            usersCacheFile(cwDir).writeText(gson.toJson(sorted))
            sorted
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Updates a ticket's assignee in Jira.
     * Pass null [accountId] to unassign the ticket.
     */
    fun updateAssignee(ticketKey: String, accountId: String?, cwDir: File?): Result<Unit> {
        return try {
            val cfg = DevConfig.load(cwDir)
            val baseUrl = cfg.jira.base_url.trimEnd('/')
            val conn = java.net.URL("$baseUrl/rest/api/3/issue/$ticketKey/assignee")
                .openConnection() as HttpURLConnection
            JiraAuth.apply(conn, cwDir)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            val body = if (accountId != null) """{"accountId":"${jsonEscape(accountId)}"}"""
            else """{"accountId":null}"""
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) Result.success(Unit)
            else {
                val err = try {
                    (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.readText()
                } catch (_: Exception) {
                    null
                }
                Result.failure(RuntimeException(err?.take(200) ?: "HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun selfCacheFile(cwDir: File) = File(cwDir, "jira-me.json")

    /** Returns the logged-in Jira user's own accountId, via /rest/api/3/myself. Cached permanently
     *  once fetched, same convention as [loadUsers] — this identity doesn't change per project. */
    fun currentAccountId(cwDir: File): String? {
        val cache = selfCacheFile(cwDir)
        if (cache.exists()) {
            try {
                val obj = JsonParser.parseString(cache.readText()).asJsonObject
                return obj.get("accountId")?.asString
            } catch (_: Exception) {
            }
        }
        return try {
            val cfg = DevConfig.load(cwDir)
            val baseUrl = cfg.jira.base_url.trimEnd('/')
            if (baseUrl.isBlank()) return null
            val conn = openJiraConn(cwDir, "$baseUrl/rest/api/3/myself")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            val accountId = JsonParser.parseString(body).asJsonObject.get("accountId")?.asString
            if (accountId != null) {
                cwDir.mkdirs()
                cache.writeText(body)
            }
            accountId
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun openJiraConn(cwDir: File, url: String): HttpURLConnection {
        val conn = java.net.URL(url).openConnection() as HttpURLConnection
        JiraAuth.apply(conn, cwDir)
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        return conn
    }
}
