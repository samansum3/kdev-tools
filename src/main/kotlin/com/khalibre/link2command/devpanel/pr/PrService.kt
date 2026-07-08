package com.khalibre.link2command.devpanel.pr

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.khalibre.link2command.devpanel.config.DevConfig
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class MergeableState { MERGEABLE, CONFLICTING, UNKNOWN }
enum class ReviewState { AWAITING, APPROVED, CHANGES_REQUESTED, COMMENTED }

data class PrReview(val author: String, val state: String)

data class MergeInfo(val mergeable: MergeableState, val isOutdated: Boolean)

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
    var mergeable: MergeableState = MergeableState.UNKNOWN,
    var isOutdated: Boolean = false
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
    fun rebasePr(
        project: Project,
        targetBranch: String? = null,
        ontoBranch: String? = null
    ): Result<String> {
        val workDir = project.basePath?.let { File(it) }
            ?: return Result.failure(RuntimeException("No project directory."))

        val currentBranchResult =
            runCmd(listOf("git", "rev-parse", "--abbrev-ref", "HEAD"), workDir)
        if (currentBranchResult.exitCode != 0)
            return Result.failure(RuntimeException("Not inside a git repository."))
        val currentBranch = currentBranchResult.stdout.trim()

        val target = targetBranch?.takeIf { it.isNotBlank() } ?: currentBranch
        val onto = ontoBranch?.takeIf { it.isNotBlank() } ?: DevConfig.load().git.base_branch

        val log = StringBuilder()

        if (currentBranch != target) {
            log.appendLine("Checking out $target...")
            val checkout = runCmd(listOf("git", "checkout", target), workDir)
            if (checkout.exitCode != 0)
                return Result.failure(RuntimeException(checkout.stderr.ifBlank { "Failed to checkout $target" }
                    .take(300)))
        } else {
            log.appendLine("Already on $target.")
        }

        // Existing remote selection logic: ticket-shaped `onto` → origin, else upstream
        val remote = if (Regex("^[A-Z]+-[0-9]+$").matches(onto)) "origin" else "upstream"

        log.appendLine("Fetching $remote/$onto...")
        val fetch = runCmd(listOf("git", "fetch", remote, onto, "--quiet"), workDir)
        if (fetch.exitCode != 0)
            return Result.failure(RuntimeException(fetch.stderr.ifBlank { "Failed to fetch $remote/$onto" }
                .take(300)))

        log.appendLine("Rebasing $target onto $remote/$onto...")
        val pull = runCmd(listOf("git", "pull", "--rebase", remote, onto), workDir)
        if (pull.exitCode != 0)
            return Result.failure(
                RuntimeException(
                    pull.stderr.ifBlank { pull.stdout }
                        .ifBlank { "Rebase failed — resolve conflicts and continue manually" }
                        .take(300)
                )
            )

        log.appendLine("Done. $target rebased onto $remote/$onto.")
        return Result.success(log.toString().trim())
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

    /** Fetch mergeable status + outdated-with-base status for a list of PR numbers in parallel */
    fun fetchMergeableStates(repo: String, numbers: List<Int>): Map<Int, MergeInfo> {
        if (numbers.isEmpty()) return emptyMap()
        val results = ConcurrentHashMap<Int, MergeInfo>()
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
                            "number,mergeable,mergeStateStatus"
                        )
                    )
                    if (result.exitCode == 0) {
                        val obj = JsonParser.parseString(result.stdout).asJsonObject
                        val mergeable = when (obj.get("mergeable")?.asString ?: "UNKNOWN") {
                            "MERGEABLE" -> MergeableState.MERGEABLE
                            "CONFLICTING" -> MergeableState.CONFLICTING
                            else -> MergeableState.UNKNOWN
                        }
                        val outdated = obj.get("mergeStateStatus")?.asString == "BEHIND"
                        results[num] = MergeInfo(mergeable, outdated)
                    }
                } catch (e: Exception) {
                    results[num] = MergeInfo(MergeableState.UNKNOWN, false)
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

    fun runCmd(
        args: List<String>,
        workDir: File? = null,
        env: Map<String, String> = emptyMap()
    ): CmdResult {
        val pb = ProcessBuilder(args).apply {
            workDir?.let { directory(it) }
            // include user's PATH so gh is found
            environment()["PATH"] = System.getenv("PATH")
                ?: "/usr/local/bin:/usr/bin:/bin:/home/${System.getProperty("user.name")}/.local/bin"
            env.forEach { (k, v) -> environment()[k] = v }
        }
        val proc = pb.start()
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        return CmdResult(stdout, stderr, exitCode)
    }
}
