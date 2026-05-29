package com.khalibre.link2command.devpanel.tickets

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.khalibre.link2command.devpanel.config.DevConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

data class JiraTicket(
    val key: String,
    val summary: String,
    val status: String,
    val assigneeName: String?,
    val assigneeEmail: String?,
    val issueType: String?,
    val priority: String?
)

data class TicketFilters(
    val parentKeys: List<String> = emptyList(),
    val fixVersion: String = "",
    val myTasks: Boolean = false,
    val hideDone: Boolean = true,
    val unassigned: Boolean = false,
    val inProgress: Boolean = false,
    val deployedUat: Boolean = false,
    val typeFilter: Set<String> = emptySet()   // empty = all types
)

object JiraService {

    private val DONE_STATUSES = listOf("Done", "Closed", "Resolved", "Merged", "Pending Release")
    private val DONE_STATUSES_NO_MERGED = listOf("Done", "Closed", "Resolved", "Pending Release")

    // ── JQL builder — mirrors build-jql script ───────────────────────────────

    fun buildJql(filters: TicketFilters, currentUserEmail: String?): String {
        val clauses = mutableListOf<String>()

        // parent keys
        if (filters.parentKeys.isNotEmpty()) {
            val keys = filters.parentKeys.joinToString(",") { "\"$it\"" }
            clauses += "parent in ($keys)"
        }

        // fix version
        if (filters.fixVersion.isNotBlank()) {
            clauses += "fixVersion = ${filters.fixVersion}"
        }

        // status / done filtering (mirrors the script logic exactly)
        val hasExplicitStatus = filters.inProgress || filters.deployedUat
        if (!hasExplicitStatus) {
            if (filters.hideDone) {
                // jql_hide_done_clause: not-done OR (non-subtask AND not-done-no-merged)
                val notDone = "status NOT IN (${DONE_STATUSES.joinToString(",") { "\"$it\"" }})"
                val notDoneNoMerged = "status NOT IN (${DONE_STATUSES_NO_MERGED.joinToString(",") { "\"$it\"" }})"
                clauses += "($notDone OR (type != \"Sub-task\" AND $notDoneNoMerged))"
            }
            // if neither hideDone nor explicit status → no status clause (show all)
        } else {
            val statuses = mutableListOf<String>()
            if (filters.inProgress) statuses += "In Progress"
            if (filters.deployedUat) statuses += "Deployed to UAT"
            if (statuses.isNotEmpty()) {
                clauses += "status IN (${statuses.joinToString(",") { "\"$it\"" }})"
            }
        }

        // owner
        if (filters.unassigned) clauses += "assignee is EMPTY"
        if (filters.myTasks && !currentUserEmail.isNullOrBlank()) {
            clauses += "assignee = currentUser()"
        }

        // type filter
        if (filters.typeFilter.isNotEmpty()) {
            val types = filters.typeFilter.joinToString(",") { "\"$it\"" }
            clauses += "type IN ($types)"
        }

        val jql = clauses.joinToString(" AND ")
        return if (jql.isBlank()) "ORDER BY created ASC"
        else "$jql ORDER BY created ASC"
    }

    // ── Jira REST API call ───────────────────────────────────────────────────

    fun searchTickets(jql: String): List<JiraTicket> {
        val cfg = DevConfig.load()
        val baseUrl = cfg.jira.base_url.trimEnd('/')
        val email = cfg.jira.email
        val token = cfg.jira.api_token

        if (baseUrl.isBlank() || email.isBlank() || token.isBlank()) {
            throw RuntimeException("Jira not configured. Please fill in Base URL, email, and API token in Config.")
        }

        val encodedJql = java.net.URLEncoder.encode(jql, "UTF-8")
        val url = URL("$baseUrl/rest/api/2/search?jql=$encodedJql&fields=summary,status,assignee,issuetype,priority&maxResults=200")

        val auth = Base64.getEncoder().encodeToString("$email:$token".toByteArray())
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Basic $auth")
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
            throw RuntimeException("Jira API error $responseCode: ${err.take(200)}")
        }

        val body = conn.inputStream.bufferedReader().readText()
        return parseSearchResponse(body)
    }

    fun currentUserEmail(): String? {
        val cfg = DevConfig.load()
        return cfg.jira.email.takeIf { it.isNotBlank() }
    }

    // ── Jira transition — mirrors transition-ticket-status / acli jira workitem transition ──

    fun transitionTicket(ticketKey: String, targetStatus: String): Result<String> {
        val cfg = DevConfig.load()
        val baseUrl = cfg.jira.base_url.trimEnd('/')
        val email = cfg.jira.email
        val token = cfg.jira.api_token
        val auth = Base64.getEncoder().encodeToString("$email:$token".toByteArray())

        return try {
            // 1. get available transitions
            val transitionsUrl = URL("$baseUrl/rest/api/2/issue/$ticketKey/transitions")
            val conn1 = transitionsUrl.openConnection() as HttpURLConnection
            conn1.setRequestProperty("Authorization", "Basic $auth")
            conn1.setRequestProperty("Accept", "application/json")
            conn1.connectTimeout = 8_000
            conn1.readTimeout = 10_000

            if (conn1.responseCode != 200) {
                return Result.failure(RuntimeException("Failed to fetch transitions (${conn1.responseCode})"))
            }

            val transBody = conn1.inputStream.bufferedReader().readText()
            val transArr = JsonParser.parseString(transBody)
                .asJsonObject.getAsJsonArray("transitions")

            val transition = transArr.firstOrNull { el ->
                val obj = el.asJsonObject
                val toStatus = obj.getAsJsonObject("to")?.get("name")?.asString ?: ""
                toStatus.equals(targetStatus, ignoreCase = true)
            }?.asJsonObject
                ?: return Result.failure(RuntimeException("Transition to '$targetStatus' not available for $ticketKey"))

            val transitionId = transition.get("id").asString

            // 2. perform transition
            val postUrl = URL("$baseUrl/rest/api/2/issue/$ticketKey/transitions")
            val conn2 = postUrl.openConnection() as HttpURLConnection
            conn2.requestMethod = "POST"
            conn2.setRequestProperty("Authorization", "Basic $auth")
            conn2.setRequestProperty("Content-Type", "application/json")
            conn2.doOutput = true
            conn2.connectTimeout = 8_000
            conn2.readTimeout = 10_000

            val body = """{"transition":{"id":"$transitionId"}}"""
            conn2.outputStream.write(body.toByteArray())

            val code = conn2.responseCode
            if (code in 200..204) {
                Result.success("$ticketKey → $targetStatus")
            } else {
                val err = conn2.errorStream?.bufferedReader()?.readText() ?: ""
                Result.failure(RuntimeException("Transition failed ($code): ${err.take(200)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Smart transition flows — mirrors in-progress, pending-qa, deployed-uat, merged ──

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
        val cfg = DevConfig.load()
        val url = "${cfg.jira.base_url.trimEnd('/')}/browse/$ticketKey"
        try {
            ProcessBuilder("xdg-open", url).start()
        } catch (e: Exception) {
            // fallback: Desktop API
            try { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } catch (_: Exception) {}
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun fetchCurrentStatus(ticketKey: String): Result<String> {
        val cfg = DevConfig.load()
        val baseUrl = cfg.jira.base_url.trimEnd('/')
        val email = cfg.jira.email
        val token = cfg.jira.api_token
        val auth = Base64.getEncoder().encodeToString("$email:$token".toByteArray())

        return try {
            val url = URL("$baseUrl/rest/api/2/issue/$ticketKey?fields=status")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Basic $auth")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            if (conn.responseCode != 200) {
                return Result.failure(RuntimeException("Failed to fetch ticket (${ conn.responseCode})"))
            }
            val body = conn.inputStream.bufferedReader().readText()
            val status = JsonParser.parseString(body)
                .asJsonObject
                .getAsJsonObject("fields")
                .getAsJsonObject("status")
                .get("name").asString
            Result.success(status)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseSearchResponse(body: String): List<JiraTicket> {
        val root = JsonParser.parseString(body).asJsonObject
        val issues = root.getAsJsonArray("issues") ?: return emptyList()
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
                val issueType = fields.getAsJsonObject("issuetype")?.get("name")?.asString
                val priority = fields.getAsJsonObject("priority")?.get("name")?.asString
                JiraTicket(key, summary, status, assigneeName, assigneeEmail, issueType, priority)
            } catch (e: Exception) {
                null
            }
        }
    }
}
