package com.khalibre.link2command.devpanel.pr

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class MergeableState { MERGEABLE, CONFLICTING, UNKNOWN }
enum class ReviewState { AWAITING, APPROVED, CHANGES_REQUESTED, COMMENTED }

data class PrReview(val author: String, val state: String)

data class PullRequest(
    val number: Int,
    val title: String,
    val author: String,
    val headRefName: String,
    val baseRefName: String,
    val updatedAt: String,
    val isDraft: Boolean,
    val labels: List<Pair<String, String>>,   // name, color
    val reviews: List<PrReview>,
    val url: String,
    var mergeable: MergeableState = MergeableState.UNKNOWN
) {
    val approvedBy: List<String>
        get() {
            val latest = mutableMapOf<String, String>()
            reviews.forEach { latest[it.author] = it.state }
            return latest.filter { it.value == "APPROVED" }.keys.sorted()
        }

    val commentedBy: List<String>
        get() {
            val latest = mutableMapOf<String, String>()
            reviews.forEach { latest[it.author] = it.state }
            return latest.filter { it.value == "COMMENTED" }.keys.sorted()
        }

    val changesRequestedBy: List<String>
        get() {
            val latest = mutableMapOf<String, String>()
            reviews.forEach { latest[it.author] = it.state }
            return latest.filter { it.value == "CHANGES_REQUESTED" }.keys.sorted()
        }

    val reviewState: ReviewState
        get() = when {
            approvedBy.isNotEmpty() && changesRequestedBy.isEmpty() -> ReviewState.APPROVED
            changesRequestedBy.isNotEmpty() -> ReviewState.CHANGES_REQUESTED
            commentedBy.isNotEmpty() -> ReviewState.COMMENTED
            else -> ReviewState.AWAITING
        }

    fun timeAgo(): String {
        return try {
            val instant = Instant.parse(updatedAt)
            val diffMs = System.currentTimeMillis() - instant.toEpochMilli()
            val mins = diffMs / 60000
            val hours = mins / 60
            val days = hours / 24
            when {
                days > 0 -> "${days}d ago"
                hours > 0 -> "${hours}h ago"
                mins > 0 -> "${mins}m ago"
                else -> "just now"
            }
        } catch (e: Exception) {
            updatedAt
        }
    }
}

object PrService {
    private val gson = Gson()

    fun rebasePr(project: Project, branch: String): Result<String> {
        val workDir = project.basePath?.let { File(it) }
        val result = runCmd(listOf("bash", "-c", "rebase $branch"), workDir)
        return if (result.exitCode == 0) Result.success(result.stdout)
        else Result.failure(RuntimeException(result.stderr.ifBlank { "Rebase failed" }))
    }

    fun updatePr(project: Project): Result<String> {
        val workDir = project.basePath?.let { File(it) }
        val result = runCmd(listOf("bash", "-c", "update-pr"), workDir)
        return if (result.exitCode == 0) Result.success(result.stdout)
        else Result.failure(RuntimeException(result.stderr.ifBlank { "update-pr failed" }))
    }

    fun checkoutBranch(project: Project, branch: String): Result<String> {
        val workDir = project.basePath?.let { File(it) }
        val result = runCmd(listOf("git", "checkout", branch), workDir)
        return if (result.exitCode == 0) Result.success("Checked out $branch")
        else Result.failure(RuntimeException(result.stderr.ifBlank { "Checkout failed" }))
    }

    /** Resolves the upstream remote repo slug (e.g. "Khalibre/crosswired-common") */
    fun upstreamRepo(project: Project): String? {
        val gitDir = findGitDir(project) ?: return null
        return try {
            val result = runCmd(listOf("git", "remote", "get-url", "upstream"), gitDir)
            result.stdout.trim()
                .removePrefix("https://github.com/")
                .removePrefix("git@github.com:")
                .removeSuffix(".git")
                .takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** Fetch open PRs (non-draft) for an optional base branch */
    fun fetchPrs(repo: String, baseBranch: String?, author: String?): List<PullRequest> {
        val args = mutableListOf(
            "gh",
            "pr",
            "list",
            "--repo",
            repo,
            "--state",
            "open",
            "--json",
            "number,title,author,labels,updatedAt,reviewDecision,url,headRefName,baseRefName,isDraft,reviews",
            "--limit",
            "100"
        )
        if (!baseBranch.isNullOrBlank()) args += listOf("--base", baseBranch)
        if (!author.isNullOrBlank()) args += listOf("--author", author)
        val result = runCmd(args)
        if (result.exitCode != 0) throw RuntimeException("gh pr list failed: ${result.stderr}")

        val arr = JsonParser.parseString(result.stdout).asJsonArray
        return arr.mapNotNull { parsePr(it.asJsonObject) }
            .filter { !it.isDraft }
    }

    /** Fetch mergeable status for a list of PR numbers in parallel */
    fun fetchMergeableStates(repo: String, numbers: List<Int>): Map<Int, MergeableState> {
        if (numbers.isEmpty()) return emptyMap()
        val threads = numbers.map { num ->
            val thread = Thread { }
            Pair(num, thread)
        }

        val results = ConcurrentHashMap<Int, MergeableState>()
        val latch = CountDownLatch(numbers.size)

        numbers.forEach { num ->
            Thread {
                try {
                    val result = runCmd(
                        listOf(
                            "gh",
                            "pr",
                            "view",
                            "$num",
                            "--repo",
                            repo,
                            "--json",
                            "number,mergeable"
                        )
                    )
                    if (result.exitCode == 0) {
                        val obj = JsonParser.parseString(result.stdout).asJsonObject
                        val state = obj.get("mergeable")?.asString ?: "UNKNOWN"
                        results[num] = when (state) {
                            "MERGEABLE" -> MergeableState.MERGEABLE
                            "CONFLICTING" -> MergeableState.CONFLICTING
                            else -> MergeableState.UNKNOWN
                        }
                    }
                } catch (e: Exception) {
                    results[num] = MergeableState.UNKNOWN
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        latch.await(30, TimeUnit.SECONDS)
        return results
    }

    /** gh pr review <number> --approve */
    fun approvePr(repo: String, number: Int): Result<String> {
        val result = runCmd(listOf("gh", "pr", "review", "$number", "--approve", "--repo", repo))
        return if (result.exitCode == 0) Result.success("PR #$number approved.")
        else Result.failure(RuntimeException(result.stderr.ifBlank { "Approve failed" }))
    }

    /** gh pr merge <number> --rebase --admin [+ optional approve first] */
    fun mergePr(repo: String, number: Int, andApprove: Boolean): Result<String> {
        if (andApprove) {
            val approveResult = approvePr(repo, number)
            if (approveResult.isFailure) return approveResult
        }
        val result =
            runCmd(listOf("gh", "pr", "merge", "$number", "--rebase", "--admin", "--repo", repo))
        return if (result.exitCode == 0) Result.success("PR #$number merged.")
        else Result.failure(RuntimeException(result.stderr.ifBlank { "Merge failed" }))
    }

    /** gh pr view <number> --web */
    fun openInBrowser(number: Int) {
        // Use Desktop API — reliable cross-platform, no PATH issues
        try {
            val result =
                runCmd(listOf("gh", "pr", "view", "$number", "--json", "url", "--jq", ".url"))
            val url = result.stdout.trim()
            if (url.startsWith("http") && result.exitCode == 0) {
                Desktop.getDesktop().browse(URI(url))
            } else {
                // fallback: construct URL from upstream repo
                throw RuntimeException("no url")
            }
        } catch (e: Exception) {
            // last resort: open PR list page
            try {
                Desktop.getDesktop().browse(URI("https://github.com"))
            } catch (_: Exception) {
            }
        }
    }

    /** Resolve current user's gh login */
    fun currentGhUser(): String? {
        val result = runCmd(listOf("gh", "api", "user", "--jq", ".login"))
        return result.stdout.trim().takeIf { it.isNotBlank() && result.exitCode == 0 }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun parsePr(obj: JsonObject): PullRequest? {
        return try {
            val number = obj.get("number").asInt
            val title = obj.get("title").asString
            val author = obj.getAsJsonObject("author").get("login").asString
            val head = obj.get("headRefName").asString
            val base = obj.get("baseRefName")?.asString ?: ""
            val updatedAt = obj.get("updatedAt").asString
            val isDraft = obj.get("isDraft")?.asBoolean ?: false
            val url = obj.get("url").asString

            val labels = obj.getAsJsonArray("labels")?.map {
                val l = it.asJsonObject
                Pair(l.get("name").asString, l.get("color")?.asString ?: "999999")
            } ?: emptyList()

            val reviews = obj.getAsJsonArray("reviews")?.mapNotNull {
                val r = it.asJsonObject
                val reviewAuthor =
                    r.getAsJsonObject("author")?.get("login")?.asString ?: return@mapNotNull null
                val state = r.get("state")?.asString ?: return@mapNotNull null
                PrReview(reviewAuthor, state)
            } ?: emptyList()

            PullRequest(number, title, author, head, base, updatedAt, isDraft, labels, reviews, url)
        } catch (e: Exception) {
            null
        }
    }

    private fun findGitDir(project: Project): File? {
        return project.basePath?.let { File(it) }
    }

    data class CmdResult(val stdout: String, val stderr: String, val exitCode: Int)

    fun runCmd(args: List<String>, workDir: File? = null): CmdResult {
        val pb = ProcessBuilder(args).apply {
            workDir?.let { directory(it) }
            // include user's PATH so gh is found
            environment()["PATH"] = System.getenv("PATH")
                ?: "/usr/local/bin:/usr/bin:/bin:/home/${System.getProperty("user.name")}/.local/bin"
        }
        val proc = pb.start()
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        return CmdResult(stdout, stderr, exitCode)
    }
}
