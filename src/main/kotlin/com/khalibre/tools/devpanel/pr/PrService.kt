package com.khalibre.tools.devpanel.pr

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.khalibre.tools.devpanel.config.DevConfig
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class MergeableState { MERGEABLE, CONFLICTING, UNKNOWN }
enum class ReviewState { AWAITING, APPROVED, CHANGES_REQUESTED, COMMENTED }

data class PrReview(val author: String, val state: String)

data class MergeInfo(val mergeable: MergeableState, val isOutdated: Boolean, val isBlocked: Boolean)

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
    var isOutdated: Boolean = false,
    var isBlocked: Boolean = false
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

        val cwDir = com.khalibre.tools.devpanel.common.ProjectPaths.cwDir(project)
        val target = targetBranch?.takeIf { it.isNotBlank() } ?: currentBranch
        val onto = ontoBranch?.takeIf { it.isNotBlank() } ?: DevConfig.load(cwDir).git.base_branch
        val parentBranch = getParentBranch(workDir, target)
        val rebaseBaseBranch = parentBranch ?: onto

        val log = StringBuilder()

        if (currentBranch == target) {
            log.appendLine("Already on $target.")
        } else {
            log.appendLine("Rebasing $target while staying on $currentBranch.")
        }

        val baseRemote = getRemote(rebaseBaseBranch)

        log.appendLine("Fetching $baseRemote/$rebaseBaseBranch...")
        val fetch = runCmd(
            listOf("git", "fetch", baseRemote, rebaseBaseBranch),
            workDir
        )
        if (fetch.exitCode != 0)
            return Result.failure(RuntimeException(fetch.stderr.ifBlank { "Failed to fetch $baseRemote/$rebaseBaseBranch" }
                .take(300)))

        log.appendLine("Rebasing $target onto $baseRemote/$rebaseBaseBranch...")
        val rebaseCmd = if (currentBranch == target) {
            listOf("git", "rebase", "$baseRemote/$rebaseBaseBranch")
        } else {
            listOf("git", "rebase", "$baseRemote/$rebaseBaseBranch", target)
        }
        val pull = runCmd(rebaseCmd, workDir)
        if (pull.exitCode != 0)
            return Result.failure(
                RuntimeException(
                    pull.stderr.ifBlank { pull.stdout }
                        .ifBlank { "Rebase failed — resolve conflicts and continue manually" }
                        .take(300)
                )
            )

        // Push in background
        ApplicationManager.getApplication().executeOnPooledThread {
            val targetRemote = getRemote(target)
            pushBranch(workDir, targetRemote, target)
        }

        // `git rebase <upstream> <branch>` implicitly checks out <branch> as a side effect
        // (per git's own docs). When the caller started on a different branch, switch back so
        // they land where they started rather than on the branch that just got rebased/pushed.
        if (currentBranch != target) {
            val restore = runCmd(listOf("git", "checkout", currentBranch), workDir)
            if (restore.exitCode == 0) {
                log.appendLine("Switched back to $currentBranch.")
            } else {
                log.appendLine(
                    "Note: couldn't switch back to $currentBranch automatically " +
                            "(${
                                restore.stderr.ifBlank { restore.stdout }.take(200)
                            }); you're still on $target."
                )
            }
        }

        log.appendLine("Done. $target rebased onto $baseRemote/$rebaseBaseBranch.")
        return Result.success(log.toString().trim())
    }

    fun updatePr(project: Project, pr: PullRequest, setImg: Boolean = false): Result<String> {
        val workDir = project.basePath?.let { File(it) }
            ?: return Result.failure(RuntimeException("No project directory."))
        val cwDir = com.khalibre.tools.devpanel.common.ProjectPaths.cwDir(project)

        val prNumber = pr.number
        val log = StringBuilder()

        // Push in background
        ApplicationManager.getApplication().executeOnPooledThread {
            val targetRemote = getRemote(pr.headRefName)
            pushBranch(workDir, targetRemote, pr.headRefName)
        }

        // ── Fetch PR ─────────────────────────────────────────────────────────
        log.appendLine("Fetching PR #$prNumber...")
        val prViewResult = runCmd(
            listOf("gh", "pr", "view", "$prNumber", "--json", "number,body,headRefName"),
            workDir
        )
        if (prViewResult.exitCode != 0)
            return Result.failure(RuntimeException("PR #$prNumber not found"))
        val prJson = try {
            JsonParser.parseString(prViewResult.stdout).asJsonObject
        } catch (e: Exception) {
            return Result.failure(RuntimeException("Failed to parse PR JSON"))
        }
        val prBody = prJson.get("body")?.takeIf { !it.isJsonNull }?.asString ?: ""
        val headBranch = prJson.get("headRefName")?.takeIf { !it.isJsonNull }?.asString
        log.appendLine("PR #$prNumber found")

        var newBody = prBody

        // ── 1. Dependency cleanup / backfill (always first) ────────────────────
        val dependLine = prBody.lines().firstOrNull { it.contains("DEPEND ON #") }
        if (dependLine != null) {
            val parentPr = Regex("DEPEND ON #(\\d+)").find(dependLine)?.groupValues?.get(1)
            if (parentPr != null) {
                log.appendLine("Checking dependency #$parentPr...")
                val state = runCmd(
                    listOf(
                        "gh",
                        "pr",
                        "view",
                        parentPr,
                        "--json",
                        "state",
                        "--jq",
                        ".state"
                    ),
                    workDir
                ).stdout.trim()
                if (state == "MERGED") {
                    log.appendLine("Removing resolved dependency...")
                    newBody = newBody.lines()
                        .filterNot { it.contains("DEPEND ON #$parentPr") }
                        .dropWhile { it.isBlank() }   // matches script's `sed '/./,$!d'`
                        .joinToString("\n")
                    log.appendLine("✔ Dependency removed")
                } else {
                    log.appendLine("Dependency still active ($state)")
                }
            }
        } else if (headBranch != null) {
            // No dependency line yet — check whether this branch actually has an unlinked
            // parent PR (e.g. it was created before the dependency detection was wired up, or
            // the line was edited out by hand) and backfill it, same as create-pr would.
            log.appendLine("No dependency found — checking for an unlinked parent PR...")
            val parentPrRef =
                CreatePrService.detectParentPrRef(workDir, headBranch, "origin/$headBranch")
            if (parentPrRef != null) {
                newBody = "### DEPEND ON $parentPrRef\n${newBody.trimStart('\n')}"
                log.appendLine("✔ Added dependency $parentPrRef")
            } else {
                log.appendLine("No parent PR found")
            }
        } else {
            log.appendLine("No dependency found")
        }

        // ── 2. Clipboard image handling (single source of truth) ──────────────
        if (ClipboardImage.hasImage()) {
            val token = DevConfig.load(cwDir).git.user_session.takeIf { it.isNotBlank() }
                ?: return Result.failure(RuntimeException("GH_SESSION_TOKEN not set — add a GitHub session token in Config"))
            val uploaded = ClipboardImage.upload(token, workDir).getOrElse {
                return Result.failure(RuntimeException("Image upload failed: ${it.message}"))
            }
            val imgTag = "<img alt=\"image\" src=\"${uploaded.url}\" />"

            if (setImg) {
                log.appendLine("Replacing images...")
                newBody = newBody.replace(Regex("<img\\s[^>]*/?\\s*>"), "").trimEnd()
                newBody = "$newBody\n$imgTag"
                log.appendLine("✔ Images replaced")
            } else {
                log.appendLine("Appending clipboard image...")
                newBody = "$newBody\n$imgTag"
                log.appendLine("✔ Image appended")
            }
        } else if (setImg) {
            return Result.failure(RuntimeException("--set-img used but no clipboard image found"))
        }

        // ── 3. No-op check ───────────────────────────────────────────────────
        if (newBody == prBody) {
            log.appendLine("No changes")
            return Result.success(log.toString().trim())
        }

        // ── 4. Update PR via REST PATCH ────────────────────────────────────────
        log.appendLine("Updating PR...")
        val remoteResult = runCmd(listOf("git", "remote", "get-url", "upstream"), workDir)
        if (remoteResult.exitCode != 0 || remoteResult.stdout.isBlank())
            return Result.failure(RuntimeException("Cannot detect repo"))
        val repo = remoteResult.stdout.trim()
            .replace(Regex(".*github\\.com[:/]"), "")
            .removeSuffix(".git")

        val ghToken = runCmd(listOf("gh", "auth", "token")).stdout.trim()
        if (ghToken.isBlank())
            return Result.failure(RuntimeException("Failed to get gh auth token"))

        return try {
            val payloadJson = Gson().toJson(mapOf("body" to newBody))
            val request = java.net.http.HttpRequest.newBuilder()
                .uri(URI("https://api.github.com/repos/$repo/pulls/$prNumber"))
                .header("Authorization", "Bearer $ghToken")
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofString(payloadJson))
                .build()
            val response = java.net.http.HttpClient.newHttpClient()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                log.appendLine("✔ PR updated")
                Result.success(log.toString().trim())
            } else {
                Result.failure(
                    RuntimeException(
                        "Update failed (${response.statusCode()}): ${
                            response.body().take(200)
                        }"
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(RuntimeException("Update failed: ${e.message}"))
        }
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
                        val stateStatus = obj.get("mergeStateStatus")?.asString
                        val outdated = stateStatus == "BEHIND"
                        val blocked = stateStatus == "BLOCKED"
                        results[num] = MergeInfo(mergeable, outdated, blocked)
                    }
                } catch (e: Exception) {
                    results[num] = MergeInfo(
                        MergeableState.UNKNOWN,
                        isOutdated = false,
                        isBlocked = false
                    )
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

    fun pushBranch(workDir: File, remote: String, targetBranch: String): Result<String> {
        val result = runCmd(
            listOf(
                "git",
                "push",
                "--force-with-lease",
                remote,
                "$targetBranch:$targetBranch"
            ),
            workDir
        )
        return if (result.exitCode == 0) Result.success("Pushed $targetBranch to $remote")
        else Result.failure(
            RuntimeException(
                result.stderr.ifBlank { result.stdout }.ifBlank { "git push failed" }.take(300)
            )
        )
    }

    private fun getParentBranch(
        workDir: File,
        branchName: String
    ): String? {
        val parentPr = CreatePrService.detectParentPrRef(workDir, branchName, logRef = branchName)
            ?.removePrefix("#")
            ?: return null

        val result = runCmd(
            listOf(
                "gh",
                "pr",
                "view",
                parentPr,
                "--json",
                "headRefName",
                "--jq",
                ".headRefName"
            ),
            workDir
        )

        return result.stdout.trim()
            .takeIf { result.exitCode == 0 && it.isNotBlank() }
    }

    internal fun getRemote(branchName: String): String {
        val remote = if (CreatePrService.TICKET_KEY_RE.matches(branchName)) "origin" else "upstream"
        return remote
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
