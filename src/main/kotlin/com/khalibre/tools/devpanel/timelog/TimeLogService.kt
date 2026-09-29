package com.khalibre.tools.devpanel.timelog

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.khalibre.tools.devpanel.config.DevConfig
import com.khalibre.tools.devpanel.pr.PrService
import com.khalibre.tools.devpanel.tickets.JiraAuth
import com.khalibre.tools.devpanel.tickets.JiraUserService
import java.io.File
import java.net.HttpURLConnection
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A single time-loggable ticket from the configured Time project, e.g. TIME-31. */
data class TimeTicketInfo(val key: String, val summary: String)

/** One worklog entry as shown in the weekly table, already resolved to the issue it belongs to.
 *  [id] is the Jira worklog id (needed to target this specific entry for deletion) — blank for
 *  entries not yet round-tripped through Jira (see [TimeLogService.addWorklog]). */
data class WorklogEntry(
    val issueKey: String,
    val id: String,
    val issueSummary: String,
    val date: LocalDate,
    val timeSpent: String,
    val comment: String
)

object TimeLogService {

    private val gson = Gson()
    private val startedFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

    fun searchQueryCacheFile(cwDir: File) = File(cwDir, "timelog-search-query.txt")

    /** Last search keyword typed into the Time code picker, remembered across dialog opens. */
    fun loadRememberedSearchQuery(cwDir: File?): String {
        val file = cwDir?.let { searchQueryCacheFile(it) } ?: return ""
        return if (file.exists()) try {
            file.readText()
        } catch (_: Exception) {
            ""
        } else ""
    }

    fun rememberSearchQuery(cwDir: File?, query: String) {
        val dir = cwDir ?: return
        try {
            dir.mkdirs()
            searchQueryCacheFile(dir).writeText(query)
        } catch (_: Exception) {
        }
    }

    fun lastTicketKeyCacheFile(cwDir: File) = File(cwDir, "timelog-last-ticket.txt")

    /** Ticket key used the last time a worklog was successfully added, so Add Time can default
     *  to it (repeated entries like "Daily stand-up" shouldn't require re-selecting every time). */
    fun loadLastTicketKey(cwDir: File?): String? {
        val file = cwDir?.let { lastTicketKeyCacheFile(it) } ?: return null
        return if (file.exists()) try {
            file.readText().trim().ifBlank { null }
        } catch (_: Exception) {
            null
        } else null
    }

    fun rememberLastTicketKey(cwDir: File?, key: String) {
        val dir = cwDir ?: return
        try {
            dir.mkdirs()
            lastTicketKeyCacheFile(dir).writeText(key)
        } catch (_: Exception) {
        }
    }

    fun lastDurationCacheFile(cwDir: File) = File(cwDir, "timelog-last-duration.txt")

    /** Duration used the last time a worklog was successfully added. */
    fun loadLastDuration(cwDir: File?): String? {
        val file = cwDir?.let { lastDurationCacheFile(it) } ?: return null
        return if (file.exists()) try {
            file.readText().trim().ifBlank { null }
        } catch (_: Exception) {
            null
        } else null
    }

    fun rememberLastDuration(cwDir: File?, duration: String) {
        val dir = cwDir ?: return
        try {
            dir.mkdirs()
            lastDurationCacheFile(dir).writeText(duration)
        } catch (_: Exception) {
        }
    }

    // ── Per-week worklog cache (instant display while a fresh fetch is in flight) ────────────

    private data class CachedWorklogEntry(
        val issueKey: String,
        val id: String,
        val issueSummary: String,
        val date: String, // ISO-8601, e.g. "2026-07-20"
        val timeSpent: String,
        val comment: String
    )

    private fun weekCacheDir(cwDir: File, accountId: String): File {
        val safe = accountId.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        return File(File(cwDir, "time-log"), safe)
    }

    private fun weekCacheFile(cwDir: File, accountId: String, weekStart: LocalDate) =
        File(weekCacheDir(cwDir, accountId), "$weekStart.json")

    /** Cached worklogs for [accountId]'s week starting [weekStart], or null if nothing's cached. */
    fun loadCachedWeek(cwDir: File?, accountId: String, weekStart: LocalDate): List<WorklogEntry>? {
        val dir = cwDir ?: return null
        val file = weekCacheFile(dir, accountId, weekStart)
        if (!file.exists()) return null
        return try {
            val type =
                object : com.google.gson.reflect.TypeToken<List<CachedWorklogEntry>>() {}.type
            val cached: List<CachedWorklogEntry> = gson.fromJson(file.readText(), type)
            cached.map {
                WorklogEntry(
                    it.issueKey,
                    it.id,
                    it.issueSummary,
                    LocalDate.parse(it.date),
                    it.timeSpent,
                    it.comment
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun cacheWeek(
        cwDir: File?,
        accountId: String,
        weekStart: LocalDate,
        entries: List<WorklogEntry>
    ) {
        val dir = cwDir ?: return
        try {
            weekCacheDir(dir, accountId).mkdirs()
            val cached = entries.map {
                CachedWorklogEntry(
                    it.issueKey,
                    it.id,
                    it.issueSummary,
                    it.date.toString(),
                    it.timeSpent,
                    it.comment
                )
            }
            weekCacheFile(dir, accountId, weekStart).writeText(gson.toJson(cached))
        } catch (_: Exception) {
        }
    }

    // ── Time-project ticket list (for the Add Time dialog's dropdown) ──────────

    fun timeTicketsCacheFile(cwDir: File) = File(cwDir, "time-tickets.json")

    /** Returns cached Time-project tickets if available, otherwise fetches from Jira and caches. */
    fun loadTimeTickets(cwDir: File): List<TimeTicketInfo> {
        val cache = timeTicketsCacheFile(cwDir)
        if (cache.exists()) {
            return try {
                JsonParser.parseString(cache.readText()).asJsonArray.map {
                    val obj = it.asJsonObject
                    TimeTicketInfo(obj.get("key").asString, obj.get("summary").asString)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        return fetchAndCacheTimeTickets(cwDir)
    }

    /** Force-fetches Time-project tickets via acli, overwrites cache, returns fresh list. */
    fun fetchAndCacheTimeTickets(cwDir: File): List<TimeTicketInfo> {
        val cfg = DevConfig.load(cwDir)
        val timeProjectKey = cfg.jira.time_project_key
        if (timeProjectKey.isBlank()) return emptyList()

        val result = PrService.runCmd(
            listOf(
                "acli", "jira", "workitem", "search",
                "--jql", "project = \"$timeProjectKey\" ORDER BY key ASC",
                "--fields", "summary",
                "--json", "--limit", "1000"
            )
        )
        if (result.exitCode != 0 || result.stdout.isBlank()) return emptyList()

        return try {
            val root = JsonParser.parseString(result.stdout)
            val issues = if (root.isJsonArray) root.asJsonArray
            else root.asJsonObject.getAsJsonArray("issues") ?: return emptyList()

            val tickets = issues.mapNotNull { el ->
                try {
                    val obj = el.asJsonObject
                    val key = obj.get("key").asString
                    val summary = obj.getAsJsonObject("fields").get("summary")?.asString ?: ""
                    TimeTicketInfo(key, summary)
                } catch (_: Exception) {
                    null
                }
            }
            cwDir.mkdirs()
            timeTicketsCacheFile(cwDir).writeText(gson.toJson(tickets))
            tickets
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Add a worklog ───────────────────────────────────────────────────────────

    /**
     * Logs [timeSpent] (Jira duration syntax, e.g. "1h 30m") against [issueKey] on [date] at
     * 9:00am local time. Mirrors the jlog CLI script's behaviour. The payload is built via Gson
     * (not manual string interpolation) so comment text is always correctly JSON-escaped.
     */
    fun addWorklog(
        issueKey: String,
        timeSpent: String,
        date: LocalDate,
        description: String,
        cwDir: File?
    ): Result<String> {
        val cfg = DevConfig.load(cwDir)
        val baseUrl = cfg.jira.base_url.trimEnd('/')
        if (baseUrl.isBlank()) return Result.failure(RuntimeException("Jira base URL not configured"))

        val started = OffsetDateTime.of(
            date.atTime(9, 0),
            ZoneId.systemDefault().rules.getOffset(date.atTime(9, 0))
        )
            .format(startedFormatter)

        val payload = JsonObject().apply {
            addProperty("timeSpent", timeSpent)
            addProperty("started", started)
            if (description.isNotBlank()) {
                add("comment", JsonObject().apply {
                    addProperty("type", "doc")
                    addProperty("version", 1)
                    add("content", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "paragraph")
                            add("content", com.google.gson.JsonArray().apply {
                                add(JsonObject().apply {
                                    addProperty("type", "text")
                                    addProperty("text", description)
                                })
                            })
                        })
                    })
                })
            }
        }

        return try {
            val conn = java.net.URL("$baseUrl/rest/api/3/issue/$issueKey/worklog")
                .openConnection() as HttpURLConnection
            JiraAuth.apply(conn, cwDir)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.outputStream.use { it.write(gson.toJson(payload).toByteArray()) }

            val code = conn.responseCode
            if (code in 200..201) {
                val body = conn.inputStream.bufferedReader().readText()
                val id = try {
                    JsonParser.parseString(body).asJsonObject.get("id")?.asString
                } catch (_: Exception) {
                    null
                }
                Result.success(id ?: "")
            } else {
                val err = try {
                    (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.readText()
                } catch (_: Exception) {
                    null
                }
                Result.failure(RuntimeException(err?.take(300) ?: "HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(RuntimeException("Add time failed: ${e.message}"))
        }
    }

    // ── Delete a worklog ─────────────────────────────────────────────────────────

    /** Removes worklog [worklogId] from [issueKey] in Jira. */
    fun deleteWorklog(issueKey: String, worklogId: String, cwDir: File?): Result<Unit> {
        val cfg = DevConfig.load(cwDir)
        val baseUrl = cfg.jira.base_url.trimEnd('/')
        if (baseUrl.isBlank()) return Result.failure(RuntimeException("Jira base URL not configured"))
        if (worklogId.isBlank()) return Result.failure(RuntimeException("Missing worklog id"))

        return try {
            val conn = java.net.URL("$baseUrl/rest/api/3/issue/$issueKey/worklog/$worklogId")
                .openConnection() as HttpURLConnection
            JiraAuth.apply(conn, cwDir)
            conn.setRequestProperty("Accept", "application/json")
            conn.requestMethod = "DELETE"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000

            val code = conn.responseCode
            if (code in 200..204) Result.success(Unit)
            else {
                val err = try {
                    (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.readText()
                } catch (_: Exception) {
                    null
                }
                Result.failure(RuntimeException(err?.take(300) ?: "HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(RuntimeException("Remove time failed: ${e.message}"))
        }
    }

    // ── Weekly aggregate fetch ───────────────────────────────────────────────────

    /**
     * Returns every worklog logged by [forAccountId] (defaults to the current Jira user) between
     * [weekStart] and [weekEnd] (inclusive), across any issue/project — not just the configured
     * Time project.
     *
     * Two-step approach:
     *   1. JQL `worklogAuthor = "<accountId>" AND worklogDate >= ... AND worklogDate <= ...`
     *      (via acli) to find which issues have relevant worklogs at all.
     *   2. For each issue, fetch its worklogs directly via REST and keep only entries that are
     *      both authored by that account AND fall inside the date range (an issue can match
     *      step 1 from one worklog but also carry unrelated worklogs from teammates or other
     *      dates that must be filtered out here).
     */
    fun fetchWeekWorklogs(
        weekStart: LocalDate,
        weekEnd: LocalDate,
        cwDir: File?,
        forAccountId: String? = null
    ): Result<List<WorklogEntry>> {
        if (cwDir == null) return Result.failure(RuntimeException("No project detected"))

        val targetAccountId = forAccountId ?: JiraUserService.currentAccountId(cwDir)
        ?: return Result.failure(RuntimeException("Couldn't resolve current Jira user"))

        val jql = "worklogAuthor = \"$targetAccountId\" AND worklogDate >= \"$weekStart\" " +
                "AND worklogDate <= \"$weekEnd\" ORDER BY key ASC"

        val searchResult = PrService.runCmd(
            listOf(
                "acli", "jira", "workitem", "search",
                "--jql", jql,
                "--fields", "summary",
                "--json", "--limit", "200"
            )
        )
        if (searchResult.exitCode != 0)
            return Result.failure(
                RuntimeException(
                    "acli error: ${
                        searchResult.stderr.take(200).ifBlank { "search failed" }
                    }"
                )
            )
        if (searchResult.stdout.isBlank()) return Result.success(emptyList())

        val issues: List<Pair<String, String>> = try {
            val root = JsonParser.parseString(searchResult.stdout)
            val arr = if (root.isJsonArray) root.asJsonArray
            else root.asJsonObject.getAsJsonArray("issues") ?: return Result.success(emptyList())
            arr.mapNotNull { el ->
                try {
                    val obj = el.asJsonObject
                    val key = obj.get("key").asString
                    val summary = obj.getAsJsonObject("fields").get("summary")?.asString ?: ""
                    key to summary
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            return Result.success(emptyList())
        }

        val entries = mutableListOf<WorklogEntry>()
        for ((key, summary) in issues) {
            entries += fetchWorklogsInRangeForAuthor(
                key,
                summary,
                targetAccountId,
                weekStart,
                weekEnd,
                cwDir
            )
        }
        return Result.success(entries)
    }

    /**
     * Fetches worklogs for a single issue, requesting only the most-recent page (issues can carry
     * thousands of historical worklogs from other users/testing — see jlog's pagination fix),
     * then filters down to [targetAccountId]'s entries within [weekStart]..[weekEnd].
     */
    private fun fetchWorklogsInRangeForAuthor(
        issueKey: String,
        issueSummary: String,
        targetAccountId: String,
        weekStart: LocalDate,
        weekEnd: LocalDate,
        cwDir: File,
        pageSize: Int = 300
    ): List<WorklogEntry> {
        val cfg = DevConfig.load(cwDir)
        val baseUrl = cfg.jira.base_url.trimEnd('/')
        if (baseUrl.isBlank()) return emptyList()

        // Cheap probe to learn the total count, so we can jump straight to the last page
        // instead of paging through potentially thousands of old entries from the start.
        val total = try {
            val probeConn = openJiraConn(
                cwDir,
                "$baseUrl/rest/api/3/issue/$issueKey/worklog?startAt=0&maxResults=1"
            )
            if (probeConn.responseCode != 200) return emptyList()
            JsonParser.parseString(probeConn.inputStream.bufferedReader().readText())
                .asJsonObject.get("total")?.asInt ?: 0
        } catch (_: Exception) {
            return emptyList()
        }

        val startAt = if (total > pageSize) total - pageSize else 0

        return try {
            val conn = openJiraConn(
                cwDir,
                "$baseUrl/rest/api/3/issue/$issueKey/worklog?startAt=$startAt&maxResults=$pageSize"
            )
            if (conn.responseCode != 200) return emptyList()
            val root =
                JsonParser.parseString(conn.inputStream.bufferedReader().readText()).asJsonObject
            val worklogs = root.getAsJsonArray("worklogs") ?: return emptyList()

            worklogs.mapNotNull { el ->
                try {
                    val obj = el.asJsonObject
                    val authorId = obj.getAsJsonObject("author")?.get("accountId")?.asString
                    if (authorId != targetAccountId) return@mapNotNull null

                    val startedStr = obj.get("started")?.asString ?: return@mapNotNull null
                    val date = OffsetDateTime.parse(startedStr, startedFormatter).toLocalDate()
                    if (date.isBefore(weekStart) || date.isAfter(weekEnd)) return@mapNotNull null

                    val id = obj.get("id")?.asString ?: ""
                    val timeSpent = obj.get("timeSpent")?.asString ?: "?"
                    val comment = extractCommentText(obj.get("comment"))
                    WorklogEntry(issueKey, id, issueSummary, date, timeSpent, comment)
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractCommentText(commentEl: com.google.gson.JsonElement?): String {
        if (commentEl == null || commentEl.isJsonNull || !commentEl.isJsonObject) return ""
        return try {
            commentEl.asJsonObject
                .getAsJsonArray("content")?.get(0)?.asJsonObject
                ?.getAsJsonArray("content")?.get(0)?.asJsonObject
                ?.get("text")?.asString ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun openJiraConn(cwDir: File, url: String): HttpURLConnection {
        val conn = java.net.URL(url).openConnection() as HttpURLConnection
        JiraAuth.apply(conn, cwDir)
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        return conn
    }
}
