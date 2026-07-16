package com.khalibre.tools.devpanel.standup

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.khalibre.tools.devpanel.config.DevConfig
import com.khalibre.tools.devpanel.pr.PrService
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

data class TicketRef(val key: String, val summary: String)

data class StandupReport(
    val yesterdayLabel: String,
    val todayLabel: String,
    val yesterdayTickets: List<TicketRef>,
    val morningTickets: List<TicketRef>,
    val currentTickets: List<TicketRef>,
    val commitsByTicket: Map<String, List<String>>
)

/** Kotlin port of the `standup-preview` shell script. All functions here do blocking
 *  process/network calls — must run off the EDT. */
object StandupService {

    private val DAY_LABEL_FMT = DateTimeFormatter.ofPattern("EEEE, MMMM dd yyyy", Locale.ENGLISH)

    fun buildReport(project: Project): Result<StandupReport> {
        val workDir = project.basePath?.let { File(it) }
            ?: return Result.failure(RuntimeException("No project directory."))

        val remoteResult = PrService.runCmd(listOf("git", "remote", "get-url", "upstream"), workDir)
        if (remoteResult.exitCode != 0 || remoteResult.stdout.isBlank())
            return Result.failure(RuntimeException("Could not get upstream repo."))
        val upstreamRepo = remoteResult.stdout.trim()
            .replace(Regex(".*github\\.com[:/]"), "").removeSuffix(".git")

        val today = LocalDate.now()
        val prevWorkday = findPrevWorkday(com.khalibre.tools.devpanel.common.ProjectPaths.cwDir(project))
        val prevWorkdayStart = "$prevWorkday 00:00"
        val prevWorkdayEnd = "$prevWorkday 23:59"
        val midnightToday = "$today 00:00"
        val nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

        val actualYesterday = today.minusDays(1)
        val yesterdayTitle = if (prevWorkday == actualYesterday)
            "Yesterday (${prevWorkday.format(DAY_LABEL_FMT)})" else prevWorkday.format(DAY_LABEL_FMT)
        val todayTitle = "This Morning (${today.format(DAY_LABEL_FMT)})"

        val yesterdayTickets = fetchTickets(
            "assignee = currentUser() AND status changed to 'In Progress' during ('$prevWorkdayStart', '$prevWorkdayEnd')"
        )
        val morningRaw = fetchTickets(
            "assignee = currentUser() AND status changed to 'In Progress' during ('$midnightToday', '$nowStr')"
        )
        val currentTickets = fetchTickets(
            "assignee = currentUser() AND status = 'In Progress' ORDER BY updated DESC"
        )
        val currentKeys = currentTickets.map { it.key }.toSet()
        val morningTickets = morningRaw.filterNot { it.key in currentKeys }

        val allKeys = LinkedHashSet<String>()
        (yesterdayTickets + morningTickets + currentTickets).forEach { allKeys += it.key }

        val commitsByTicket = mutableMapOf<String, List<String>>()
        allKeys.forEach { key ->
            val commits = try {
                ticketCommits(upstreamRepo, key)
            } catch (_: Exception) {
                emptyList()
            }
            if (commits.isNotEmpty()) commitsByTicket[key] = commits
        }

        return Result.success(
            StandupReport(
                yesterdayTitle,
                todayTitle,
                yesterdayTickets,
                morningTickets,
                currentTickets,
                commitsByTicket
            )
        )
    }

    // ── Previous workday (skips weekends + Cambodia public holidays) ────────────

    private fun findPrevWorkday(cwDir: File?): LocalDate {
        val apiKey = DevConfig.load(cwDir).calendarific.api_key
        var check = LocalDate.now().minusDays(1)
        var holidays: Set<LocalDate> = emptySet()
        var holidaysLoaded = false

        repeat(14) {
            if (check.dayOfWeek == DayOfWeek.SATURDAY || check.dayOfWeek == DayOfWeek.SUNDAY) {
                check = check.minusDays(1)
                return@repeat
            }
            if (!holidaysLoaded && apiKey.isNotBlank()) {
                holidays = fetchHolidays(apiKey, check.year)
                holidaysLoaded = true
            }
            if (check !in holidays) return check
            check = check.minusDays(1)
        }
        return LocalDate.now().minusDays(1)
    }

    private fun fetchHolidays(apiKey: String, year: Int): Set<LocalDate> {
        return try {
            val url =
                "https://calendarific.com/api/v2/holidays?api_key=$apiKey&country=KH&year=$year&type=national"
            val request = HttpRequest.newBuilder(URI(url)).GET().build()
            val response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return emptySet()
            val root = JsonParser.parseString(response.body()).asJsonObject
            root.getAsJsonObject("response")?.getAsJsonArray("holidays")?.mapNotNull { el ->
                try {
                    val iso = el.asJsonObject.getAsJsonObject("date").get("iso").asString
                    LocalDate.parse(iso.substring(0, 10))
                } catch (_: Exception) {
                    null
                }
            }?.toSet() ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    // ── Jira tickets via acli ────────────────────────────────────────────────────

    private fun fetchTickets(jql: String): List<TicketRef> {
        val result = PrService.runCmd(
            listOf(
                "acli",
                "jira",
                "workitem",
                "search",
                "--jql",
                jql,
                "--fields",
                "key,summary",
                "--json"
            )
        )
        if (result.exitCode != 0 || result.stdout.isBlank()) return emptyList()
        return try {
            val parsed = JsonParser.parseString(result.stdout)
            if (!parsed.isJsonArray) return emptyList()
            parsed.asJsonArray.mapNotNull { el ->
                val obj = el.asJsonObject
                val key = obj.get("key")?.asString ?: return@mapNotNull null
                val summary = obj.getAsJsonObject("fields")?.get("summary")?.asString ?: key
                TicketRef(key, summary)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Commits for a ticket's PR, excluding a dependent PR's commits ───────────

    private fun ticketCommits(upstreamRepo: String, ticket: String): List<String> {
        val prListResult = PrService.runCmd(
            listOf(
                "gh", "pr", "list", "--repo", upstreamRepo, "--head", ticket, "--state", "all",
                "--json", "number,state,body", "--limit", "1"
            )
        )
        if (prListResult.exitCode != 0 || prListResult.stdout.isBlank()) return emptyList()
        val arr = try {
            JsonParser.parseString(prListResult.stdout).asJsonArray
        } catch (_: Exception) {
            return emptyList()
        }
        if (arr.size() == 0) return emptyList()
        val prObj = arr[0].asJsonObject
        val prNumber = prObj.get("number")?.asInt ?: return emptyList()
        val prState = prObj.get("state")?.asString ?: ""
        val prBody = prObj.get("body")?.takeIf { !it.isJsonNull }?.asString ?: ""

        val allCommits = fetchCommitMessages(upstreamRepo, prNumber)
        if (allCommits.isEmpty()) return emptyList()

        if (prState == "OPEN") {
            val dependPr =
                Regex("DEPEND ON #(\\d+)").find(prBody)?.groupValues?.get(1)?.toIntOrNull()
            if (dependPr != null) {
                val dependCommits = fetchCommitMessages(upstreamRepo, dependPr).toSet()
                if (dependCommits.isNotEmpty()) return allCommits.filterNot { it in dependCommits }
            }
        }
        return allCommits
    }

    private fun fetchCommitMessages(repo: String, prNumber: Int): List<String> {
        val result = PrService.runCmd(
            listOf(
                "gh",
                "api",
                "repos/$repo/pulls/$prNumber/commits",
                "--jq",
                ".[].commit.message | split(\"\\n\")[0]"
            )
        )
        if (result.exitCode != 0) return emptyList()
        return result.stdout.lines().filter { it.isNotBlank() }
    }

    // ── Preview text (plugin dialog) ─────────────────────────────────────────────

    fun buildPlainPreview(report: StandupReport): String {
        fun section(title: String, emoji: String, tickets: List<TicketRef>): String = buildString {
            append("\n$emoji $title\n").append("─".repeat(50)).append("\n")
            if (tickets.isEmpty()) {
                append("  no tickets\n")
            } else {
                tickets.forEach { t ->
                    append("  ${t.key}  ${t.summary}\n")
                    val commits = report.commitsByTicket[t.key]
                    if (!commits.isNullOrEmpty()) commits.forEach { c -> append("    ↳ $c\n") }
                    else append("    ↳ no commits\n")
                }
            }
        }
        return buildString {
            append(section(report.yesterdayLabel, "📅", report.yesterdayTickets))
            append(section(report.todayLabel, "🌅", report.morningTickets))
            append("\n🎯 Current Tasks\n").append("─".repeat(50)).append("\n")
            if (report.currentTickets.isEmpty()) append("  no current tasks detected\n")
            else report.currentTickets.forEach { t -> append("  ${t.key}  ${t.summary}\n") }
        }
    }

    // ── Telegram send ────────────────────────────────────────────────────────────

    private fun escapeHtml(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun buildTelegramMessage(report: StandupReport): String {
        fun section(title: String, emoji: String, tickets: List<TicketRef>): String = buildString {
            append("\n$emoji <b>${escapeHtml(title)}</b>\n")
            if (tickets.isEmpty()) {
                append("  <i>no tickets</i>\n")
            } else {
                tickets.forEachIndexed { idx, t ->
                    val summary = escapeHtml(t.summary)
                    val key = escapeHtml(t.key)
                    val commits = report.commitsByTicket[t.key]
                    if (!commits.isNullOrEmpty()) {
                        append("  ${idx + 1}. <b>$key</b>: $summary\n")
                        append("<blockquote expandable>\n")
                        commits.forEach { c -> append("↳ ${escapeHtml(c)}\n") }
                        append("</blockquote>\n")
                    } else {
                        append("  ${idx + 1}. $key: $summary <i>(no commits)</i>\n")
                    }
                }
            }
        }
        return buildString {
            append("🗓 <b>Standup — ${LocalDate.now().format(DAY_LABEL_FMT)}</b>\n")
            append(section(report.yesterdayLabel, "📅", report.yesterdayTickets))
            append(section(report.todayLabel, "🌅", report.morningTickets))
            append("\n🎯 <b>Current Tasks</b>\n")
            if (report.currentTickets.isEmpty()) {
                append("  <i>no current tasks detected</i>")
            } else {
                report.currentTickets.forEach { t ->
                    append("  <b>${escapeHtml(t.key)}</b>: ${escapeHtml(t.summary)}\n")
                }
            }
        }
    }

    fun sendToTelegram(report: StandupReport, cwDir: File?): Result<Unit> {
        val cfg = DevConfig.load(cwDir).telegram
        if (cfg.bot_token.isBlank() || cfg.chat_id.isBlank())
            return Result.failure(RuntimeException("Telegram bot token / chat id not configured"))

        val payload = Gson().toJson(
            mapOf(
                "chat_id" to cfg.chat_id,
                "text" to buildTelegramMessage(report),
                "parse_mode" to "HTML"
            )
        )
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI("https://api.telegram.org/bot${cfg.bot_token}/sendMessage"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
            val response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) Result.success(Unit)
            else Result.failure(
                RuntimeException(
                    "Telegram API responded with HTTP ${response.statusCode()}: ${
                        response.body().take(200)
                    }"
                )
            )
        } catch (e: Exception) {
            Result.failure(RuntimeException("Failed to send: ${e.message}"))
        }
    }
}
