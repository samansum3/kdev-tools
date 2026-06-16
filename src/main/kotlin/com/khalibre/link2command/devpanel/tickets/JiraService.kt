package com.khalibre.link2command.devpanel.tickets

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.khalibre.link2command.devpanel.config.DevConfig
import com.khalibre.link2command.devpanel.pr.PrService
import java.io.File

data class JiraTicket(
    val key: String,
    val summary: String,
    val status: String,
    val assigneeName: String?,
    val assigneeEmail: String?,
    val issueType: String?,
    val issueTypeIconUrl: String?,
    val priority: String?,
    val priorityIconUrl: String?,
    val availableTransitions: List<String>? = null
)

data class TicketFilters(
    val parentKeys: List<String> = emptyList(),
    val fixVersion: String = "",
    val myTasks: Boolean = false,
    val unassigned: Boolean = false,
    // Active status badges — each maps directly to a Jira status name
    val activeStatuses: Set<String> = emptySet(),
    // hide-done modifier (only applied when no explicit status badges are active)
    val hideDone: Boolean = false,
    val typeFilter: Set<String> = emptySet(),
    val notTypeFilter: Set<String> = emptySet()
)

object JiraService {

    fun buildJql(filters: TicketFilters, currentUserEmail: String?): String {
        val clauses = mutableListOf<String>()

        // Scope: parent keys OR fix version
        val scopeParts = mutableListOf<String>()
        if (filters.parentKeys.isNotEmpty())
            scopeParts += "parent in (${filters.parentKeys.joinToString(",") { "\"$it\"" }})"
        if (filters.fixVersion.isNotBlank())
            scopeParts += "fixVersion = ${filters.fixVersion}"
        if (scopeParts.isNotEmpty())
            clauses += if (scopeParts.size == 1) scopeParts[0] else "(${scopeParts.joinToString(" OR ")})"

        // Owner filter
        if (filters.myTasks) clauses += "assignee = currentUser()"
        if (filters.unassigned) clauses += "assignee is EMPTY"

        // Status filter
        when {
            filters.activeStatuses.isNotEmpty() ->
                clauses += "status IN (${filters.activeStatuses.joinToString(",") { "\"$it\"" }})"

            filters.hideDone -> clauses += buildHideDoneClause()
        }

        // Type filters
        if (filters.typeFilter.isNotEmpty())
            clauses += "type IN (${filters.typeFilter.joinToString(",") { "\"$it\"" }})"
        if (filters.notTypeFilter.isNotEmpty())
            clauses += "type NOT IN (${filters.notTypeFilter.joinToString(",") { "\"$it\"" }})"

        val jql = clauses.joinToString(" AND ")
        return if (jql.isBlank()) "ORDER BY created ASC" else "$jql ORDER BY created ASC"
    }

    /**
     * Builds the Hide Done JQL clause from TicketConfig.doneStatusesByType.
     *
     * For each type that has configured done statuses we emit:
     *   NOT (type = "X" AND status IN ("s1","s2",...))
     * All such terms are AND-ed together so a ticket must not be "done" in any of its type rules.
     * Types with no configured done statuses are left unrestricted.
     * If no types are configured at all, returns an empty string (no filtering).
     */
    private fun buildHideDoneClause(): String {
        val ticketCfg = DevConfig.load().ticket
        val doneMap = ticketCfg.doneStatusesByType.filter { it.value.isNotEmpty() }
        if (doneMap.isEmpty()) return ""

        val terms = doneMap.map { (typeName, statuses) ->
            val statusList = statuses.joinToString(",") { "\"$it\"" }
            "NOT (type = \"$typeName\" AND status IN ($statusList))"
        }
        return terms.joinToString(" AND ") { "($it)" }
    }

    fun searchTickets(jql: String): List<JiraTicket> {
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
        File("/tmp/result.json").writeText(result.stdout) // Debug output
        return parseAcliResponse(result.stdout)
    }

    /**
     * Returns list of (targetStatus, transitionName) pairs.
     */
    fun fetchAvailableTransitions(ticketKey: String): List<Pair<String, String>> {
        return try {
            val cfg = DevConfig.load()
            val baseUrl = cfg.jira.base_url.trimEnd('/')
            val auth = java.util.Base64.getEncoder()
                .encodeToString("${cfg.jira.email}:${cfg.jira.api_token}".toByteArray())
            val conn = java.net.URL("$baseUrl/rest/api/3/issue/$ticketKey/transitions")
                .openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("Authorization", "Basic $auth")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 8_000; conn.readTimeout = 10_000
            if (conn.responseCode != 200) return emptyList()
            val root = JsonParser.parseString(conn.inputStream.bufferedReader().readText())
            root.asJsonObject.getAsJsonArray("transitions")
                ?.mapNotNull {
                    val obj = it.asJsonObject
                    val targetStatus =
                        obj.getAsJsonObject("to")?.get("name")?.asString ?: return@mapNotNull null
                    val transitionName = obj.get("name")?.asString ?: targetStatus
                    Pair(targetStatus, transitionName)
                }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun assignToMe(ticketKey: String): Result<String> {
        val result = PrService.runCmd(
            listOf(
                "acli", "jira", "workitem", "assign",
                "--key", ticketKey, "--assignee", "@me", "--yes"
            )
        )
        return if (result.exitCode == 0) Result.success("Assigned")
        else Result.failure(RuntimeException(result.stderr.take(200)))
    }

    fun pickTicket(project: Project, ticketKey: String): Result<String> {
        val workDir = project.basePath?.let { java.io.File(it) }
        val gitResult = PrService.runCmd(listOf("git", "checkout", "-b", ticketKey), workDir)
        if (gitResult.exitCode != 0)
            return Result.failure(RuntimeException(gitResult.stderr.ifBlank { "git checkout -b $ticketKey failed" }))
        assignToMe(ticketKey)
        return transitionToInProgress(ticketKey)
    }

    fun currentUserEmail(): String? = DevConfig.load().jira.email.takeIf { it.isNotBlank() }

    fun transitionTicket(ticketKey: String, targetStatus: String): Result<String> {
        val result = PrService.runCmd(
            listOf(
                "acli", "jira", "workitem", "transition",
                "--key", ticketKey, "--status", targetStatus, "--yes"
            )
        )
        return if (result.exitCode == 0) Result.success("$ticketKey → $targetStatus")
        else Result.failure(RuntimeException(result.stderr.ifBlank { result.stdout }.take(200)))
    }

    fun transitionToInProgress(ticketKey: String): Result<String> {
        val current = fetchCurrentStatus(ticketKey).getOrElse { return Result.failure(it) }
        return when (current) {
            "In Progress" -> Result.success("Already In Progress")
            "To Do" -> {
                transitionTicket(ticketKey, "Ready").getOrElse { return Result.failure(it) }
                transitionTicket(ticketKey, "In Progress")
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
        val result = PrService.runCmd(
            listOf(
                "acli", "jira", "workitem", "view", ticketKey,
                "--fields", "status", "--json"
            )
        )
        if (result.exitCode != 0 || result.stdout.isBlank())
            return Result.failure(RuntimeException(result.stderr.take(200)))
        return try {
            val item = JsonParser.parseString(result.stdout).let {
                if (it.isJsonArray) it.asJsonArray.first().asJsonObject else it.asJsonObject
            }
            Result.success(
                item.getAsJsonObject("fields").getAsJsonObject("status").get("name").asString
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseAcliResponse(body: String): List<JiraTicket> {
        val root = JsonParser.parseString(body)
        val issues = if (root.isJsonArray) root.asJsonArray
        else root.asJsonObject.getAsJsonArray("issues") ?: return emptyList()
        return parseIssueArray(issues)
    }

    private fun parseIssueArray(issues: com.google.gson.JsonArray): List<JiraTicket> {
        val jiraConfig = DevConfig.load().jira
        val baseUrl = jiraConfig.base_url
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

                JiraTicket(
                    key = key,
                    summary = fields.get("summary")?.asString ?: "(no summary)",
                    status = fields.getAsJsonObject("status")?.get("name")?.asString ?: "Unknown",
                    assigneeName = assignee?.get("displayName")?.asString,
                    assigneeEmail = assignee?.get("emailAddress")?.asString,
                    issueType = issueTypeObj?.get("name")?.asString,
                    issueTypeIconUrl = fixIconUrl(baseUrl, issueTypeObj?.get("iconUrl")?.asString),
                    priority = priorityObj?.get("name")?.asString,
                    priorityIconUrl = fixIconUrl(baseUrl, priorityObj?.get("iconUrl")?.asString)
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun fixIconUrl(baseUrl: String, url: String?) =
        url?.replace(
            "https://jira-prod-ap-18-2.prod.atl-paas.net",
            baseUrl
        )
}
