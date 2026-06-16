package com.khalibre.link2command.devpanel.tickets

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.khalibre.link2command.devpanel.config.DevConfig
import java.io.File
import java.net.HttpURLConnection

/**
 * Loads and caches Jira project statuses and issue types.
 *
 * Cache files live under  .git/cw/
 *   jira-statuses.json  — JSON array of status name strings
 *   jira-types.json     — JSON array of { name, iconUrl } objects
 *
 * Cache is permanent once written. Explicit reload (via TicketConfigPanel action buttons)
 * deletes the cache file then calls the fetch function again.
 */
object JiraMetaService {

    data class IssueTypeInfo(val name: String, val iconUrl: String)

    private val gson = Gson()

    // ── Cache file helpers ────────────────────────────────────────────────────

    fun statusCacheFile(cwDir: File) = File(cwDir, "jira-statuses.json")
    fun typesCacheFile(cwDir: File) = File(cwDir, "jira-types.json")

    /** Disk-cache file for a type icon, keyed by sanitised type name. e.g. Sub-task.png */
    fun typeIconCacheFile(cwDir: File, typeName: String): File {
        val safe = typeName.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        return File(File(cwDir, "icons"), "$safe.png")
    }

    // ── Statuses ─────────────────────────────────────────────────────────────

    /** Returns cached statuses if available, otherwise fetches from Jira and caches. */
    fun loadStatuses(cwDir: File): List<String> {
        val cache = statusCacheFile(cwDir)
        if (cache.exists()) {
            return try {
                JsonParser.parseString(cache.readText()).asJsonArray.map { it.asString }
            } catch (_: Exception) {
                emptyList()
            }
        }
        return fetchAndCacheStatuses(cwDir)
    }

    /** Force-fetches from Jira, overwrites cache, returns fresh list. */
    fun fetchAndCacheStatuses(cwDir: File): List<String> {
        return try {
            val cfg = DevConfig.load()
            val baseUrl = cfg.jira.base_url.trimEnd('/')
            val project = cfg.jira.project_key
            if (baseUrl.isBlank() || project.isBlank()) return emptyList()

            val conn = openJiraConn(cfg, "$baseUrl/rest/api/3/project/$project/statuses")
            if (conn.responseCode != 200) return emptyList()

            val root = JsonParser.parseString(conn.inputStream.bufferedReader().readText())
            val names = mutableSetOf<String>()
            root.asJsonArray.forEach { issueTypeEl ->
                issueTypeEl.asJsonObject.getAsJsonArray("statuses")?.forEach { statusEl ->
                    statusEl.asJsonObject.get("name")?.asString?.let { names.add(it) }
                }
            }
            val sorted = names.sorted()
            cwDir.mkdirs()
            statusCacheFile(cwDir).writeText(gson.toJson(sorted))
            sorted
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Issue types ───────────────────────────────────────────────────────────

    /** Returns cached types if available, otherwise fetches from Jira and caches. */
    fun loadTypes(cwDir: File): List<IssueTypeInfo> {
        val cache = typesCacheFile(cwDir)
        if (cache.exists()) {
            return try {
                JsonParser.parseString(cache.readText()).asJsonArray.map {
                    val obj = it.asJsonObject
                    IssueTypeInfo(obj.get("name").asString, obj.get("iconUrl").asString)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        return fetchAndCacheTypes(cwDir)
    }

    /** Force-fetches from Jira, overwrites cache, returns fresh list. */
    fun fetchAndCacheTypes(cwDir: File): List<IssueTypeInfo> {
        return try {
            val cfg = DevConfig.load()
            val baseUrl = cfg.jira.base_url.trimEnd('/')
            val project = cfg.jira.project_key
            if (baseUrl.isBlank() || project.isBlank()) return emptyList()

            val conn = openJiraConn(cfg, "$baseUrl/rest/api/3/project/$project")
            if (conn.responseCode != 200) return emptyList()

            val root =
                JsonParser.parseString(conn.inputStream.bufferedReader().readText()).asJsonObject
            val types = root.getAsJsonArray("issueTypes")?.mapNotNull { el ->
                val obj = el.asJsonObject
                val name = obj.get("name")?.asString ?: return@mapNotNull null
                val iconUrl = obj.get("iconUrl")?.asString ?: return@mapNotNull null
                IssueTypeInfo(name, fixIconUrl(iconUrl))
            } ?: emptyList()

            cwDir.mkdirs()
            typesCacheFile(cwDir).writeText(gson.toJson(types))
            types
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun openJiraConn(cfg: DevConfig, url: String): HttpURLConnection {
        val conn = java.net.URL(url).openConnection() as HttpURLConnection
        JiraAuth.apply(conn)
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        return conn
    }

    private fun fixIconUrl(url: String) =
        url.replace(
            "https://jira-prod-ap-18-2.prod.atl-paas.net",
            "https://khalibre.atlassian.net"
        )
}
