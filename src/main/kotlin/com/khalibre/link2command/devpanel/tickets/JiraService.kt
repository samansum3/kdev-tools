package com.khalibre.link2command.devpanel.tickets

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.khalibre.link2command.devpanel.config.DevConfig
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
    val myTasks: Boolean = false,
    val hideDone: Boolean = true,
    val unassigned: Boolean = false,
    val isTodo: Boolean = false,
    val inProgress: Boolean = false,
    val deployedUat: Boolean = false,
    val typeFilter: Set<String> = emptySet()
)

object JiraService {

    // Mirrors build-jql.sh DONE_STATUSES / DONE_STATUSES_NO_MERGED
    private val DONE_STATUSES = listOf("Done", "Closed", "Resolved", "Merged", "Pending Release")
    private val DONE_STATUSES_NO_MERGED = listOf("Done", "Closed", "Resolved", "Pending Release")

    // Mirrors jql_not_done()
    private fun jqlNotDone() =
        "status NOT IN (${DONE_STATUSES.joinToString(",") { "\"$it\"" }})"

    // Mirrors jql_not_done_no_merged()
    private fun jqlNotDoneNoMerged() =
        "status NOT IN (${DONE_STATUSES_NO_MERGED.joinToString(",") { "\"$it\"" }})"

    // Mirrors jql_unassigned_or_failed_qa()
    private fun jqlUnassignedOrFailedQa() =
        "((assignee is EMPTY AND ${jqlNotDone()}) OR status = \"Failed QA\")"

    // Mirrors jql_hide_done_clause()
    private fun jqlHideDoneClause() =
        "(${jqlNotDone()} OR (type != \"Sub-task\" AND ${jqlNotDoneNoMerged()}))"

    /**
     * Mirrors build-jql.sh build_jql() logic exactly.
     *
     * Default (no flags) → unassigned-or-failed-QA  (same as subtasks.sh default)
     * --hide-done        → hide-done clause
     * --all / show-all   → no status filter
     * --mine             → assignee = currentUser()
     * --unassigned       → assignee is EMPTY
     * explicit statuses  → status IN (...)
     * --for-dev is implicit: non-dev types excluded when fixVersion is used
     */
    fun buildJql(filters: TicketFilters): String {
        val clauses = mutableListOf<String>()

        if (filters.parentKeys.isNotEmpty())
            clauses += "parent in (${filters.parentKeys.joinToString(",") { "\"$it\"" }})"
        if (filters.fixVersion.isNotBlank())
            clauses += "fixVersion = ${filters.fixVersion}"

        val hasExplicitStatus = filters.inProgress || filters.deployedUat
        val hasAssigneeFilter = filters.myTasks || filters.unassigned   // ← new

        when {
            hasExplicitStatus -> {
                val statuses = mutableListOf<String>()
                if (filters.inProgress) statuses += "In Progress"
                if (filters.deployedUat) statuses += "Deployed to UAT"
                clauses += "status IN (${statuses.joinToString(",") { "\"$it\"" }})"
            }

            filters.hideDone -> clauses += jqlHideDoneClause()
            // Only apply the default unassigned-or-failed-QA when no explicit assignee
            // filter is active — otherwise currentUser() + assignee is EMPTY = 0 results
            !hasAssigneeFilter -> clauses += jqlUnassignedOrFailedQa()
            // hasAssigneeFilter without hideDone/explicitStatus → no status clause (show all)
        }

        if (filters.unassigned) clauses += "assignee is EMPTY"
        if (filters.myTasks) clauses += "assignee = currentUser()"

        if (filters.fixVersion.isNotBlank())
            clauses += "type not in (\"Operations\", \"Test Report\", \"Release Procedure\", \"Translation Update\")"

        if (filters.typeFilter.isNotEmpty())
            clauses += "type IN (${filters.typeFilter.joinToString(",") { "\"$it\"" }})"

        val jql = clauses.joinToString(" AND ")
        return if (jql.isBlank()) "ORDER BY created ASC" else "$jql ORDER BY created ASC"
    }

    /** PUT /rest/api/2/issue/<key>/assignee  with accountId = null means "assign to current user"
     *  Jira Cloud accepts {"accountId": null} to assign to the authenticated user's own account,
     *  but the reliable way is to first resolve our own accountId then assign it. */
    fun assignToMe(ticketKey: String): Result<String> {
        val cfg = DevConfig.load()
        val baseUrl = cfg.jira.base_url.trimEnd('/')
        val auth = Base64.getEncoder()
            .encodeToString("${cfg.jira.email}:${cfg.jira.api_token}".toByteArray())
        return try {
            // 1. Resolve current user's accountId via /rest/api/3/myself
            val meConn = URL("$baseUrl/rest/api/3/myself").openConnection() as HttpURLConnection
            meConn.setRequestProperty("Authorization", "Basic $auth")
            meConn.setRequestProperty("Accept", "application/json")
            meConn.connectTimeout = 8_000; meConn.readTimeout = 10_000
            if (meConn.responseCode != 200)
                return Result.failure(RuntimeException("Could not resolve current user (${meConn.responseCode})"))
            val accountId = JsonParser.parseString(meConn.inputStream.bufferedReader().readText())
                .asJsonObject.get("accountId").asString

            // 2. Assign
            val conn =
                URL("$baseUrl/rest/api/2/issue/$ticketKey/assignee").openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Authorization", "Basic $auth")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true; conn.connectTimeout = 8_000; conn.readTimeout = 10_000
            conn.outputStream.write("""{"accountId":"$accountId"}""".toByteArray())
            val code = conn.responseCode
            if (code in 200..204) Result.success(accountId)
            else Result.failure(RuntimeException("Assign failed ($code)"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Mirrors new-ticket.sh / pick-ticket.sh:
     *  1. git checkout -b <key>
     *  2. Assign ticket to self
     *  3. Transition to In Progress (smart: To Do → Ready → In Progress)
     */
    fun pickTicket(project: Project, ticketKey: String): Result<String> {
        // 1. git checkout -b
        val workDir = project.basePath?.let { java.io.File(it) }
        val gitResult = com.khalibre.link2command.devpanel.pr.PrService.runCmd(
            listOf("git", "checkout", "-b", ticketKey), workDir
        )
        if (gitResult.exitCode != 0)
            return Result.failure(RuntimeException(gitResult.stderr.ifBlank { "git checkout -b $ticketKey failed" }))

        // 2. Assign to self (best-effort — don't fail the whole operation)
        assignToMe(ticketKey)   // ignore result, mirrors shell's || warn behaviour

        // 3. Transition to In Progress
        return transitionToInProgress(ticketKey)
    }

    fun searchTickets(jql: String): List<JiraTicket> {
        val cfg = DevConfig.load()
        val baseUrl = cfg.jira.base_url.trimEnd('/')
        val email = cfg.jira.email
        val token = cfg.jira.api_token
        if (baseUrl.isBlank() || email.isBlank() || token.isBlank())
            throw RuntimeException("Jira not configured. Please fill in Base URL, email, and API token in Config.")
        val auth = Base64.getEncoder().encodeToString("$email:$token".toByteArray())
        val url = URL("$baseUrl/rest/api/3/search/jql")
        val requestBody = """{"jql":"${
            jql.replace("\\", "\\\\").replace("\"", "\\\"")
        }", "fields":["summary","status","assignee","issuetype","priority"], "maxResults":200}"""
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Basic $auth")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.doOutput = true; conn.connectTimeout = 10_000; conn.readTimeout = 15_000
        conn.outputStream.use { it.write(requestBody.toByteArray()) }
        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
            throw RuntimeException("Jira API error $responseCode: ${err.take(200)}")
        }
        return parseSearchResponse(conn.inputStream.bufferedReader().readText())
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

    private fun parseSearchResponse(body: String): List<JiraTicket> {
        val issues =
            JsonParser.parseString(body).asJsonObject.getAsJsonArray("issues") ?: return emptyList()
        return issues.mapNotNull { el ->
            try {
                val obj = el.asJsonObject
                val key = obj.get("key").asString
                val fields = obj.getAsJsonObject("fields")
                val summary = fields.get("summary")?.asString ?: "(no summary)"
                val status = fields.getAsJsonObject("status")?.get("name")?.asString ?: "Unknown"
                val assignee = fields.getAsJsonObject("assignee")
                val assigneeName = assignee?.get("displayName")?.asString
                val assigneeEmail = assignee?.get("emailAddress")?.asString
                val issueTypeObj = fields.getAsJsonObject("issuetype")
                val issueType = issueTypeObj?.get("name")?.asString
                val issueTypeIconUrl = issueTypeObj?.get("iconUrl")?.asString
                val priorityObj = fields.getAsJsonObject("priority")
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
