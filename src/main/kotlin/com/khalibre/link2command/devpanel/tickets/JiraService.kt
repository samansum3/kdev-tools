package com.khalibre.link2command.devpanel.tickets

import com.google.gson.JsonParser
import com.khalibre.link2command.devpanel.config.DevConfig
import com.khalibre.link2command.devpanel.pr.PrService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

data class JiraTicket(
    val key: String,
    val summary: String,
    val status: String,
    val assigneeName: String?,
    val assigneeEmail: String?,
    val issueType: String?,
    val issueTypeIconUrl: String?,
    val priority: String?,
    val priorityIconUrl: String?
)

data class TicketFilters(
    val parentKeys: List<String> = emptyList(),
    val fixVersion: String = "",
    // owner (mutually exclusive)
    val myTasks: Boolean = false,
    val unassigned: Boolean = false,
    // status badges — each maps directly to a Jira status
    val filterToDo: Boolean = false,
    val filterInProgress: Boolean = false,
    val filterPrOpen: Boolean = false,
    val filterMerged: Boolean = false,
    val filterDeployedUat: Boolean = false,
    val filterPendingQa: Boolean = false,
    val filterDone: Boolean = false,
    // hide-done modifier (only applied when no explicit status badges are active)
    val hideDone: Boolean = false,
    val typeFilter: Set<String> = emptySet()
)

object JiraService {

    private val DONE_STATUSES = listOf("Done", "Closed", "Resolved", "Merged", "Pending Release")
    private val DONE_STATUSES_NO_MERGED = listOf("Done", "Closed", "Resolved", "Pending Release")

    private fun jqlNotDone() =
        "status NOT IN (${DONE_STATUSES.joinToString(",") { "\"$it\"" }})"

    private fun jqlNotDoneNoMerged() =
        "status NOT IN (${DONE_STATUSES_NO_MERGED.joinToString(",") { "\"$it\"" }})"

    private fun jqlHideDoneClause() =
        "(${jqlNotDone()} OR (type != \"Sub-task\" AND ${jqlNotDoneNoMerged()}))"

    fun buildJql(filters: TicketFilters, currentUserEmail: String?): String {
        val clauses = mutableListOf<String>()

        // Scope: parent keys OR fix version (combined with OR, not separate AND clauses)
        val scopeParts = mutableListOf<String>()
        if (filters.parentKeys.isNotEmpty())
            scopeParts += "parent in (${filters.parentKeys.joinToString(",") { "\"$it\"" }})"
        if (filters.fixVersion.isNotBlank())
            scopeParts += "fixVersion = ${filters.fixVersion}"
        if (scopeParts.isNotEmpty())
            clauses += if (scopeParts.size == 1) scopeParts[0] else "(${scopeParts.joinToString(" OR ")})"

        // Owner filter — mutually exclusive, never both
        if (filters.myTasks) clauses += "assignee = currentUser()"
        if (filters.unassigned) clauses += "assignee is EMPTY"

        // Status filter — only when at least one badge is active
        val activeStatuses = mutableListOf<String>()
        if (filters.filterToDo) activeStatuses += "To Do"
        if (filters.filterInProgress) activeStatuses += "In Progress"
        if (filters.filterPrOpen) activeStatuses += "PR Open"
        if (filters.filterMerged) activeStatuses += "Merged"
        if (filters.filterDeployedUat) activeStatuses += "Deployed to UAT"
        if (filters.filterPendingQa) activeStatuses += "Pending QA"
        if (filters.filterDone) activeStatuses += "Done"

        when {
            activeStatuses.isNotEmpty() ->
                clauses += "status IN (${activeStatuses.joinToString(",") { "\"$it\"" }})"

            filters.hideDone -> clauses += jqlHideDoneClause()
            // No status badges active → no status clause at all
        }

        // Type filter — only when at least one type badge is active
        if (filters.typeFilter.isNotEmpty())
            clauses += "type IN (${filters.typeFilter.joinToString(",") { "\"$it\"" }})"

        val jql = clauses.joinToString(" AND ")
        return if (jql.isBlank()) "ORDER BY created ASC" else "$jql ORDER BY created ASC"
    }

    fun searchTickets(jql: String): List<JiraTicket> {
        // Use acli via PrService.runCmd — inherits correct PATH, separates stdout/stderr
        val result = PrService.runCmd(
            listOf(
                "acli", "jira", "workitem", "search",
                "--jql", jql,
                "--fields", "summary,status,assignee,issuetype,priority",
                "--json", "--limit", "200"
            )
        )
        if (result.exitCode != 0 || result.stdout.isBlank())
            throw RuntimeException(
                "acli error: ${
                    result.stderr.take(200).ifBlank { result.stdout.take(200) }
                        .ifBlank { "no output" }
                }"
            )
        File("/tmp/result.json").writeText(result.stdout)
        return parseAcliResponse(result.stdout)
    }

    fun currentUserEmail(): String? = DevConfig.load().jira.email.takeIf { it.isNotBlank() }

    fun transitionTicket(ticketKey: String, targetStatus: String): Result<String> {
        val cfg = DevConfig.load()
        val baseUrl = cfg.jira.base_url.trimEnd('/')
        val auth = Base64.getEncoder()
            .encodeToString("${cfg.jira.email}:${cfg.jira.api_token}".toByteArray())
        return try {
            val conn1 =
                URL("$baseUrl/rest/api/2/issue/$ticketKey/transitions").openConnection() as HttpURLConnection
            conn1.setRequestProperty("Authorization", "Basic $auth")
            conn1.setRequestProperty("Accept", "application/json")
            conn1.connectTimeout = 8_000; conn1.readTimeout = 10_000
            if (conn1.responseCode != 200)
                return Result.failure(RuntimeException("Failed to fetch transitions (${conn1.responseCode})"))
            val transArr = JsonParser.parseString(conn1.inputStream.bufferedReader().readText())
                .asJsonObject.getAsJsonArray("transitions")
            val transition = transArr.firstOrNull { el ->
                el.asJsonObject.getAsJsonObject("to")?.get("name")?.asString?.equals(
                    targetStatus,
                    ignoreCase = true
                ) == true
            }?.asJsonObject
                ?: return Result.failure(RuntimeException("Transition to '$targetStatus' not available for $ticketKey"))
            val conn2 =
                URL("$baseUrl/rest/api/2/issue/$ticketKey/transitions").openConnection() as HttpURLConnection
            conn2.requestMethod = "POST"
            conn2.setRequestProperty("Authorization", "Basic $auth")
            conn2.setRequestProperty("Content-Type", "application/json")
            conn2.doOutput = true; conn2.connectTimeout = 8_000; conn2.readTimeout = 10_000
            conn2.outputStream.write("""{"transition":{"id":"${transition.get("id").asString}"}}""".toByteArray())
            val code = conn2.responseCode
            if (code in 200..204) Result.success("$ticketKey → $targetStatus")
            else Result.failure(
                RuntimeException(
                    "Transition failed ($code): ${
                        conn2.errorStream?.bufferedReader()?.readText()?.take(200)
                    }"
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun transitionToInProgress(ticketKey: String): Result<String> {
        val current = fetchCurrentStatus(ticketKey).getOrElse { return Result.failure(it) }
        return when (current) {
            "In Progress" -> Result.success("Already In Progress")
            "To Do" -> {
                transitionTicket(
                    ticketKey,
                    "Ready"
                ).getOrElse { return Result.failure(it) }; transitionTicket(
                    ticketKey,
                    "In Progress"
                )
            }

            else -> transitionTicket(ticketKey, "In Progress")
        }
    }

    fun transitionToPendingQa(ticketKey: String) = transitionTicket(ticketKey, "Pending QA")
    fun transitionToDeployedUat(ticketKey: String) = transitionTicket(ticketKey, "Deployed to UAT")
    fun transitionToMerged(ticketKey: String) = transitionTicket(ticketKey, "Merged")

    fun openTicketInBrowser(ticketKey: String) {
        val url = "${DevConfig.load().jira.base_url.trimEnd('/')}/browse/$ticketKey"
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        } catch (_: Exception) {
        }
    }

    private fun fetchCurrentStatus(ticketKey: String): Result<String> {
        val cfg = DevConfig.load()
        val auth = Base64.getEncoder()
            .encodeToString("${cfg.jira.email}:${cfg.jira.api_token}".toByteArray())
        return try {
            val conn =
                URL("${cfg.jira.base_url.trimEnd('/')}/rest/api/2/issue/$ticketKey?fields=status").openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Basic $auth")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 8_000; conn.readTimeout = 10_000
            if (conn.responseCode != 200) return Result.failure(RuntimeException("Failed to fetch ticket (${conn.responseCode})"))
            Result.success(
                JsonParser.parseString(conn.inputStream.bufferedReader().readText())
                    .asJsonObject.getAsJsonObject("fields").getAsJsonObject("status")
                    .get("name").asString
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Parses acli --json output: a raw JSON array [{key, fields:{...}}, ...] */
    private fun parseAcliResponse(body: String): List<JiraTicket> {
        val root = JsonParser.parseString(body)
        val issues = if (root.isJsonArray) root.asJsonArray
        else root.asJsonObject.getAsJsonArray("issues") ?: return emptyList()
        return parseIssueArray(issues)
    }

    private fun parseSearchResponse(body: String): List<JiraTicket> {
        val issues =
            JsonParser.parseString(body).asJsonObject.getAsJsonArray("issues") ?: return emptyList()
        return parseIssueArray(issues)
    }

    private fun parseIssueArray(issues: com.google.gson.JsonArray): List<JiraTicket> {
        return issues.mapNotNull { el ->
            try {
                val obj = el.asJsonObject
                val key = obj.get("key").asString
                val fields = obj.getAsJsonObject("fields")

                val assigneeEl = fields.get("assignee")
                val assignee =
                    if (assigneeEl != null && !assigneeEl.isJsonNull) assigneeEl.asJsonObject else null

                val issueTypeEl = fields.get("issuetype")
                val issueTypeObj =
                    if (issueTypeEl != null && !issueTypeEl.isJsonNull) issueTypeEl.asJsonObject else null

                val priorityEl = fields.get("priority")
                val priorityObj =
                    if (priorityEl != null && !priorityEl.isJsonNull) priorityEl.asJsonObject else null

                val summary = fields.get("summary")?.asString ?: "(no summary)"
                val status = fields.getAsJsonObject("status")?.get("name")?.asString ?: "Unknown"
                val assigneeName = assignee?.get("displayName")?.asString
                val assigneeEmail = assignee?.get("emailAddress")?.asString
                val issueType = issueTypeObj?.get("name")?.asString
                val issueTypeIconUrl = issueTypeObj?.get("iconUrl")?.asString
                val priority = priorityObj?.get("name")?.asString
                val priorityIconUrl = priorityObj?.get("iconUrl")?.asString
                JiraTicket(
                    key,
                    summary,
                    status,
                    assigneeName,
                    assigneeEmail,
                    issueType,
                    issueTypeIconUrl,
                    priority,
                    priorityIconUrl
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
