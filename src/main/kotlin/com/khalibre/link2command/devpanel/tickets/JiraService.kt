package com.khalibre.link2command.devpanel.tickets

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
    val inProgress: Boolean = false,
    val deployedUat: Boolean = false,
    val typeFilter: Set<String> = emptySet()
)

object JiraService {

    private val DONE_STATUSES = listOf("Done", "Closed", "Resolved", "Merged", "Pending Release")
    private val DONE_STATUSES_NO_MERGED = listOf("Done", "Closed", "Resolved", "Pending Release")

    fun buildJql(filters: TicketFilters, currentUserEmail: String?): String {
        val clauses = mutableListOf<String>()
        if (filters.parentKeys.isNotEmpty())
            clauses += "parent in (${filters.parentKeys.joinToString(",") { "\"$it\"" }})"
        if (filters.fixVersion.isNotBlank())
            clauses += "fixVersion = ${filters.fixVersion}"
        val hasExplicitStatus = filters.inProgress || filters.deployedUat
        if (!hasExplicitStatus) {
            if (filters.hideDone) {
                val notDone = "status NOT IN (${DONE_STATUSES.joinToString(",") { "\"$it\"" }})"
                val notDoneNoMerged = "status NOT IN (${DONE_STATUSES_NO_MERGED.joinToString(",") { "\"$it\"" }})"
                clauses += "($notDone OR (type != \"Sub-task\" AND $notDoneNoMerged))"
            }
        } else {
            val statuses = mutableListOf<String>()
            if (filters.inProgress) statuses += "In Progress"
            if (filters.deployedUat) statuses += "Deployed to UAT"
            if (statuses.isNotEmpty()) clauses += "status IN (${statuses.joinToString(",") { "\"$it\"" }})"
        }
        if (filters.unassigned) clauses += "assignee is EMPTY"
        if (filters.myTasks && !currentUserEmail.isNullOrBlank()) clauses += "assignee = currentUser()"
        if (filters.typeFilter.isNotEmpty()) clauses += "type IN (${filters.typeFilter.joinToString(",") { "\"$it\"" }})"
        val jql = clauses.joinToString(" AND ")
        return if (jql.isBlank()) "ORDER BY created ASC" else "$jql ORDER BY created ASC"
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
        val requestBody = """{"jql":"${jql.replace("\\","\\\\").replace("\"","\\\"")}", "fields":["summary","status","assignee","issuetype","priority"], "maxResults":200}"""
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
        val auth = Base64.getEncoder().encodeToString("${cfg.jira.email}:${cfg.jira.api_token}".toByteArray())
        return try {
            val conn1 = URL("$baseUrl/rest/api/2/issue/$ticketKey/transitions").openConnection() as HttpURLConnection
            conn1.setRequestProperty("Authorization", "Basic $auth")
            conn1.setRequestProperty("Accept", "application/json")
            conn1.connectTimeout = 8_000; conn1.readTimeout = 10_000
            if (conn1.responseCode != 200)
                return Result.failure(RuntimeException("Failed to fetch transitions (${conn1.responseCode})"))
            val transArr = JsonParser.parseString(conn1.inputStream.bufferedReader().readText())
                .asJsonObject.getAsJsonArray("transitions")
            val transition = transArr.firstOrNull { el ->
                el.asJsonObject.getAsJsonObject("to")?.get("name")?.asString?.equals(targetStatus, ignoreCase = true) == true
            }?.asJsonObject ?: return Result.failure(RuntimeException("Transition to '$targetStatus' not available for $ticketKey"))
            val conn2 = URL("$baseUrl/rest/api/2/issue/$ticketKey/transitions").openConnection() as HttpURLConnection
            conn2.requestMethod = "POST"
            conn2.setRequestProperty("Authorization", "Basic $auth")
            conn2.setRequestProperty("Content-Type", "application/json")
            conn2.doOutput = true; conn2.connectTimeout = 8_000; conn2.readTimeout = 10_000
            conn2.outputStream.write("""{"transition":{"id":"${transition.get("id").asString}"}}""".toByteArray())
            val code = conn2.responseCode
            if (code in 200..204) Result.success("$ticketKey → $targetStatus")
            else Result.failure(RuntimeException("Transition failed ($code): ${conn2.errorStream?.bufferedReader()?.readText()?.take(200)}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    fun transitionToInProgress(ticketKey: String): Result<String> {
        val current = fetchCurrentStatus(ticketKey).getOrElse { return Result.failure(it) }
        return when (current) {
            "In Progress" -> Result.success("Already In Progress")
            "To Do" -> { transitionTicket(ticketKey, "Ready").getOrElse { return Result.failure(it) }; transitionTicket(ticketKey, "In Progress") }
            else -> transitionTicket(ticketKey, "In Progress")
        }
    }

    fun transitionToPendingQa(ticketKey: String) = transitionTicket(ticketKey, "Pending QA")
    fun transitionToDeployedUat(ticketKey: String) = transitionTicket(ticketKey, "Deployed to UAT")
    fun transitionToMerged(ticketKey: String) = transitionTicket(ticketKey, "Merged")

    fun openTicketInBrowser(ticketKey: String) {
        val url = "${DevConfig.load().jira.base_url.trimEnd('/')}/browse/$ticketKey"
        try { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } catch (_: Exception) {}
    }

    private fun fetchCurrentStatus(ticketKey: String): Result<String> {
        val cfg = DevConfig.load()
        val auth = Base64.getEncoder().encodeToString("${cfg.jira.email}:${cfg.jira.api_token}".toByteArray())
        return try {
            val conn = URL("${cfg.jira.base_url.trimEnd('/')}/rest/api/2/issue/$ticketKey?fields=status").openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Basic $auth")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 8_000; conn.readTimeout = 10_000
            if (conn.responseCode != 200) return Result.failure(RuntimeException("Failed to fetch ticket (${conn.responseCode})"))
            Result.success(JsonParser.parseString(conn.inputStream.bufferedReader().readText())
                .asJsonObject.getAsJsonObject("fields").getAsJsonObject("status").get("name").asString)
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun parseSearchResponse(body: String): List<JiraTicket> {
        val issues = JsonParser.parseString(body).asJsonObject.getAsJsonArray("issues") ?: return emptyList()
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
                JiraTicket(key, summary, status, assigneeName, assigneeEmail, issueType, issueTypeIconUrl, priority, priorityIconUrl)
            } catch (e: Exception) { null }
        }
    }
}
