package com.khalibre.tools.devpanel.tickets

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.khalibre.tools.devpanel.config.DevConfig
import com.khalibre.tools.devpanel.pr.PrService
import com.khalibre.tools.devpanel.tickets.JiraService.buildJql
import com.khalibre.tools.devpanel.tickets.icons.IconUtils
import java.io.File

data class JiraTicket(
    val key: String,
    val summary: String,
    var status: String,
    var statusColorName: String? = null,
    var assigneeName: String?,
    var assigneeEmail: String?,
    var assigneeAccountId: String? = null,
    val issueType: String?,
    val issueTypeIconUrl: String?,
    val priority: String?,
    val priorityIconUrl: String?,
    var availableTransitions: List<String>? = null
)

data class TicketFilters(
    val parentKeys: List<String> = emptyList(),
    val linkedKeys: List<String> = emptyList(),
    val fixVersion: String = "",
    val myTasks: Boolean = false,
    val unassigned: Boolean = false,
    // Active status badges — each maps directly to a Jira status name
    val activeStatuses: Set<String> = emptySet(),
    // not-status badges — each maps directly to a Jira status name excluded via NOT IN
    val notStatusFilter: Set<String> = emptySet(),
    // hide-done modifier (only applied when no explicit status badges are active)
    val hideDone: Boolean = false,
    val typeFilter: Set<String> = emptySet(),
    val notTypeFilter: Set<String> = emptySet()
)

data class JiraCreatedIssue(val key: String, val id: String)

data class JiraAttachment(val id: String, val filename: String)

object JiraService {
    /**
     * Creates a new Jira issue via REST POST. Pass [parentKey] + [subtaskTypeId] together for a
     * subtask (issue type referenced by id, since that's what createmeta gives us); leave both
     * null and pass [typeName] instead for a top-level ticket (issue type referenced by name,
     * matching the existing project-wide type cache which only has names).
     *
     * [descriptionAdf] is an optional pre-built ADF document node (from TipTapToAdf). When null,
     * [descriptionHtml] is used as a fallback and converted via JiraRichText.htmlToAdf().
     * If both are null/blank, the ticket is created with no description.
     */
    fun createTicket(
        summary: String,
        descriptionAdf: JsonObject? = null,
        descriptionHtml: String? = null,
        parentKey: String?,
        typeName: String?,
        subtaskTypeId: String?,
        cwDir: File?
    ): Result<JiraCreatedIssue> {
        val cfg = DevConfig.load(cwDir)
        val baseUrl = cfg.jira.base_url.trimEnd('/')
        val projectKey = cfg.jira.project_key
        if (baseUrl.isBlank() || projectKey.isBlank())
            return Result.failure(RuntimeException("Jira base URL / project key not configured"))

        val issueTypeField = JsonObject().apply {
            when {
                subtaskTypeId != null -> addProperty("id", subtaskTypeId)
                !typeName.isNullOrBlank() -> addProperty("name", typeName)
                else -> return Result.failure(RuntimeException("No issue type selected"))
            }
        }

        // Resolve description: prefer pre-built ADF, fall back to HTML→ADF conversion
        val resolvedDescription: JsonObject? = when {
            descriptionAdf != null -> descriptionAdf
            !descriptionHtml.isNullOrBlank() && !JiraRichText.isBlankHtml(descriptionHtml) ->
                JiraRichText.htmlToAdf(descriptionHtml)

            else -> null
        }

        val fields = JsonObject().apply {
            add("project", JsonObject().apply { addProperty("key", projectKey) })
            addProperty("summary", summary)
            add("issuetype", issueTypeField)
            if (!parentKey.isNullOrBlank()) add(
                "parent",
                JsonObject().apply { addProperty("key", parentKey) })
            if (resolvedDescription != null)
                add("description", resolvedDescription)
        }
        val payload = JsonObject().apply { add("fields", fields) }

        return try {
            val request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI("$baseUrl/rest/api/3/issue"))
                .header("Authorization", JiraAuth.basicHeaderValue(cwDir))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(Gson().toJson(payload)))
                .build()
            val response = java.net.http.HttpClient.newHttpClient()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 201) {
                val body = JsonParser.parseString(response.body()).asJsonObject
                val key = body.get("key").asString
                val id = body.get("id").asString
                Result.success(JiraCreatedIssue(key, id))
            } else {
                Result.failure(
                    RuntimeException(
                        "Create failed (${response.statusCode()}): ${
                            response.body().take(300)
                        }"
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(RuntimeException("Create failed: ${e.message}"))
        }
    }

    /** Uploads [file] as an attachment on [ticketKey] via Jira's REST API (multipart/form-data,
     *  `X-Atlassian-Token: no-check` required for CSRF bypass on this specific endpoint). Returns
     *  the attachment's real Jira id (needed to reference it from an ADF `media` node) — not just
     *  a human-readable message. */
    fun uploadAttachment(
        ticketKey: String,
        file: File,
        filename: String,
        mimeType: String = "image/png",
        cwDir: File? = null
    ): Result<JiraAttachment> {
        val cfg = DevConfig.load(cwDir)
        val baseUrl = cfg.jira.base_url.trimEnd('/')
        if (baseUrl.isBlank()) return Result.failure(RuntimeException("Jira base URL not configured"))

        return try {
            val boundary = "----DevPanelBoundary${System.currentTimeMillis()}"
            val header = "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
                    "Content-Type: $mimeType\r\n\r\n"
            val footer = "\r\n--$boundary--\r\n"
            val bodyBytes = header.toByteArray() + file.readBytes() + footer.toByteArray()

            val request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI("$baseUrl/rest/api/3/issue/$ticketKey/attachments"))
                .header("Authorization", JiraAuth.basicHeaderValue(cwDir))
                .header("X-Atlassian-Token", "no-check")
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build()
            val response = java.net.http.HttpClient.newHttpClient()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() in 200..201) {
                val parsed = JsonParser.parseString(response.body())
                val first = if (parsed.isJsonArray) parsed.asJsonArray.firstOrNull()?.asJsonObject
                else parsed.asJsonObject
                val id = first?.get("id")?.asString
                if (id != null) Result.success(JiraAttachment(id, filename))
                else Result.failure(RuntimeException("Attachment upload succeeded but no id in response"))
            } else Result.failure(
                RuntimeException(
                    "Attachment upload failed (${response.statusCode()}): ${
                        response.body().take(200)
                    }"
                )
            )
        } catch (e: Exception) {
            Result.failure(RuntimeException("Attachment upload failed: ${e.message}"))
        }
    }

    /** Overwrites [ticketKey]'s description with [descriptionAdf] via REST PUT — used to patch in
     *  the final description (with real `media` references) after uploading embedded images,
     *  since they can't be uploaded until the ticket — and its numeric id — exist. */
    fun updateDescription(ticketKey: String, descriptionAdf: JsonObject, cwDir: File? = null): Result<Unit> {
        val cfg = DevConfig.load(cwDir)
        val baseUrl = cfg.jira.base_url.trimEnd('/')
        if (baseUrl.isBlank()) return Result.failure(RuntimeException("Jira base URL not configured"))

        val payload = JsonObject().apply {
            add("fields", JsonObject().apply { add("description", descriptionAdf) })
        }

        return try {
            val request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI("$baseUrl/rest/api/3/issue/$ticketKey"))
                .header("Authorization", JiraAuth.basicHeaderValue(cwDir))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(Gson().toJson(payload)))
                .build()
            val response = java.net.http.HttpClient.newHttpClient()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..204) Result.success(Unit)
            else Result.failure(
                RuntimeException(
                    "Description update failed (${response.statusCode()}): ${
                        response.body().take(200)
                    }"
                )
            )
        } catch (e: Exception) {
            Result.failure(RuntimeException("Description update failed: ${e.message}"))
        }
    }

    fun buildJql(filters: TicketFilters, currentUserEmail: String?, cwDir: File?): String {
        val clauses = mutableListOf<String>()

        // Scope: parent keys OR linked-to keys OR fix version
        val scopeParts = mutableListOf<String>()
        if (filters.parentKeys.isNotEmpty())
            scopeParts += "parent in (${filters.parentKeys.joinToString(",") { "\"$it\"" }})"
        if (filters.linkedKeys.isNotEmpty())
            scopeParts += filters.linkedKeys.joinToString(" OR ") { "issue in linkedIssues(\"$it\")" }
                .let { if (filters.linkedKeys.size > 1) "($it)" else it }
        if (filters.fixVersion.isNotBlank())
            scopeParts += "fixVersion = ${filters.fixVersion}"
        if (scopeParts.isNotEmpty())
            clauses += if (scopeParts.size == 1) scopeParts[0] else "(${scopeParts.joinToString(" OR ")})"

        // Owner filter — "My Tasks" and "Unassigned" are independent toggles, OR'd together
        // when both are on (an AND here would always be a contradiction: a ticket can't be
        // both assigned to me and unassigned at once).
        val ownerParts = mutableListOf<String>()
        if (filters.myTasks) ownerParts += "assignee = currentUser()"
        if (filters.unassigned) ownerParts += "assignee is EMPTY"
        if (ownerParts.isNotEmpty())
            clauses += if (ownerParts.size == 1) ownerParts[0] else "(${ownerParts.joinToString(" OR ")})"

        // Status filter
        when {
            filters.activeStatuses.isNotEmpty() ->
                clauses += "status IN (${filters.activeStatuses.joinToString(",") { "\"$it\"" }})"

            filters.hideDone -> clauses += buildHideDoneClause(cwDir)
        }

        // Not-status filter (always applied, independent of status/hideDone)
        if (filters.notStatusFilter.isNotEmpty())
            clauses += "status NOT IN (${filters.notStatusFilter.joinToString(",") { "\"$it\"" }})"

        // Type filters
        if (filters.typeFilter.isNotEmpty())
            clauses += "type IN (${filters.typeFilter.joinToString(",") { "\"$it\"" }})"
        if (filters.notTypeFilter.isNotEmpty())
            clauses += "type NOT IN (${filters.notTypeFilter.joinToString(",") { "\"$it\"" }})"

        val jql = clauses.joinToString(" AND ")
        return if (jql.isBlank()) "ORDER BY Rank ASC" else "$jql ORDER BY Rank ASC"
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
    private fun buildHideDoneClause(cwDir: File?): String {
        val ticketCfg = DevConfig.load(cwDir).ticket
        val doneMap = ticketCfg.doneStatusesByType.filter { it.value.isNotEmpty() }
        if (doneMap.isEmpty()) return ""

        val terms = doneMap.map { (typeName, statuses) ->
            val statusList = statuses.joinToString(",") { "\"$it\"" }
            "NOT (type = \"$typeName\" AND status IN ($statusList))"
        }
        return terms.joinToString(" AND ") { "($it)" }
    }

    fun searchTickets(jql: String, cwDir: File?): List<JiraTicket> {
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
        return parseAcliResponse(result.stdout, cwDir)
    }

    /**
     * Re-fetches a single ticket using the current filter JQL (so the result still respects
     * the same scoping/visibility rules), narrowed to that one ticket via "AND key = <key>".
     *
     * Used after an assignee change: rather than reloading the entire ticket list, we only
     * need to know whether this one ticket should still be visible under the active filters,
     * and if so, fetch its fresh fields.
     *
     * Returns the updated [JiraTicket], or null if the ticket no longer matches the current
     * filters (e.g. an "Unassigned" filter was active and the ticket now has an assignee).
     */
    fun refreshTicket(currentJql: String, ticketKey: String, cwDir: File?): JiraTicket? {
        val scopedJql = scopeJqlToKey(currentJql, ticketKey)
        val result = PrService.runCmd(
            listOf(
                "acli", "jira", "workitem", "search",
                "--jql", scopedJql,
                "--fields", "summary,status,assignee,issuetype,priority",
                "--json", "--limit", "1"
            )
        )
        if (result.exitCode != 0 || result.stdout.isBlank()) return null
        return parseAcliResponse(result.stdout, cwDir).firstOrNull()
    }

    /**
     * Inserts "key = <ticketKey>" as an AND-ed clause ahead of any ORDER BY clause in [jql].
     * [jql] is expected in the shape produced by [buildJql]: "<clauses> ORDER BY Rank ASC"
     * or just "ORDER BY Rank ASC" when there are no clauses.
     */
    private fun scopeJqlToKey(jql: String, ticketKey: String): String {
        val orderByIdx = jql.indexOf("ORDER BY")
        val clauses = if (orderByIdx >= 0) jql.substring(0, orderByIdx).trim() else jql.trim()
        val orderBy = if (orderByIdx >= 0) jql.substring(orderByIdx) else "ORDER BY created ASC"
        val keyClause = "key = \"$ticketKey\""
        val combined = if (clauses.isBlank()) keyClause else "($clauses) AND $keyClause"
        return "$combined $orderBy"
    }

    /**
     * Returns list of (targetStatus, transitionName) pairs.
     */
    fun fetchAvailableTransitions(ticketKey: String, cwDir: File?): List<Pair<String, String>> {
        return try {
            val cfg = DevConfig.load(cwDir)
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

    fun currentUserEmail(cwDir: File?): String? = DevConfig.load(cwDir).jira.email.takeIf { it.isNotBlank() }

    /**
     * `acli jira workitem view <key> --fields summary --json` — same call the `create-pr`
     * shell script makes to build the "TICKET-123: <summary>" PR title.
     */
    fun fetchTicketSummary(ticketKey: String): Result<String> {
        val result = PrService.runCmd(
            listOf("acli", "jira", "workitem", "view", ticketKey, "--fields", "summary", "--json")
        )
        if (result.exitCode != 0 || result.stdout.isBlank())
            return Result.failure(
                RuntimeException(
                    "Failed to fetch Jira ticket '$ticketKey'. Is acli authenticated? Run: acli jira auth login --web"
                )
            )
        return try {
            val root = JsonParser.parseString(result.stdout)
            val item =
                if (root.isJsonArray) root.asJsonArray.first().asJsonObject else root.asJsonObject
            val summary = item.getAsJsonObject("fields").get("summary").asString.trim()
            if (summary.isBlank())
                Result.failure(RuntimeException("Jira ticket '$ticketKey' returned an empty summary."))
            else Result.success(summary)
        } catch (e: Exception) {
            Result.failure(RuntimeException("Failed to parse summary from acli output."))
        }
    }

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

    fun ticketUrl(ticketKey: String, cwDir: File?): String =
        "${DevConfig.load(cwDir).jira.base_url.trimEnd('/')}/browse/$ticketKey"

    fun openTicketInBrowser(ticketKey: String, cwDir: File?) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI(ticketUrl(ticketKey, cwDir)))
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

    private fun parseAcliResponse(body: String, cwDir: File?): List<JiraTicket> {
        val root = JsonParser.parseString(body)
        val issues = if (root.isJsonArray) root.asJsonArray
        else root.asJsonObject.getAsJsonArray("issues") ?: return emptyList()
        return parseIssueArray(issues, cwDir)
    }

    private fun parseIssueArray(issues: com.google.gson.JsonArray, cwDir: File?): List<JiraTicket> {
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
                val statusObj = fields.getAsJsonObject("status")
                val statusColorName = statusObj?.getAsJsonObject("statusCategory")
                    ?.get("colorName")?.asString

                JiraTicket(
                    key = key,
                    summary = fields.get("summary")?.asString ?: "(no summary)",
                    status = statusObj?.get("name")?.asString ?: "Unknown",
                    statusColorName = statusColorName,
                    assigneeName = assignee?.get("displayName")?.asString,
                    assigneeEmail = assignee?.get("emailAddress")?.asString,
                    assigneeAccountId = assignee?.get("accountId")?.asString,
                    issueType = issueTypeObj?.get("name")?.asString,
                    issueTypeIconUrl = fixIconUrl(issueTypeObj?.get("iconUrl")?.asString, cwDir),
                    priority = priorityObj?.get("name")?.asString,
                    priorityIconUrl = fixIconUrl(priorityObj?.get("iconUrl")?.asString, cwDir)
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun fixIconUrl(url: String?, cwDir: File?) = IconUtils.fixIconUrl(url, cwDir)
}
