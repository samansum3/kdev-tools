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
    var isBlocked: Boolean = false,
    // Review threads that are outdated, unresolved, and started by the current user — populated
    // alongside mergeable/isOutdated/isBlocked in PrPanel.refresh(). Drives the "Resolve
    // comments" button: shown iff non-empty.
    var resolvableThreadIds: List<String> = emptyList()
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

        val log = StringBuilder()

        if (currentBranch != target) {
            log.appendLine("Checking out $target...")
            val checkout = runCmd(listOf("git", "checkout", target), workDir)
            if (checkout.exitCode != 0)
                return Result.failure(
                    RuntimeException(
                        checkout.stderr.ifBlank { checkout.stdout }
                            .ifBlank { "Failed to checkout $target" }
                            .take(300)
                    )
                )
        } else {
            log.appendLine("Already on $target.")
        }

        log.appendLine("Fetching latest branches...")
        val fetchAll = runCmd(listOf("git", "fetch", "--all", "--prune"), workDir)
        if (fetchAll.exitCode != 0) {
            log.appendLine(
                "Warning: fetch --all failed (${
                    fetchAll.stderr.ifBlank { fetchAll.stdout }.take(200)
                }); parent detection may be based on stale refs."
            )
        }

        val parentBranch = CreatePrService.detectParentBranchName(workDir, target, logRef = target)
        val rebaseBaseBranch = parentBranch ?: onto
        val baseRemote = getRemote(rebaseBaseBranch)

        if (parentBranch != null) {
            log.appendLine("Found parent branch: $parentBranch")
        } else {
            log.appendLine("No open parent branch found — using base branch $onto")
        }

        // ── Rebase ───────────────────────────────────────────────────────────
        log.appendLine("Rebasing $target onto $baseRemote/$rebaseBaseBranch...")
        val pull = runCmd(listOf("git", "pull", "--rebase", baseRemote, rebaseBaseBranch), workDir)
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

        // Restore original branch if we switched to check out target above.
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

                    val removeLabel = removeDependentLabel(prNumber, workDir)
                    if (removeLabel.exitCode == 0) {
                        log.appendLine("✔ Removed Dependent label")
                    } else {
                        log.appendLine(
                            "Note: couldn't remove Dependent label (${
                                removeLabel.stderr.ifBlank { removeLabel.stdout }.take(200)
                            })"
                        )
                    }
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

                if ("Dependent" in existingRepoLabels(workDir)) {
                    val addLabel =
                        runCmd(
                            listOf("gh", "pr", "edit", "$prNumber", "--add-label", "Dependent"),
                            workDir
                        )
                    if (addLabel.exitCode == 0) {
                        log.appendLine("✔ Added Dependent label")
                    } else {
                        log.appendLine(
                            "Note: couldn't add Dependent label (${
                                addLabel.stderr.ifBlank { addLabel.stdout }.take(200)
                            })"
                        )
                    }
                }
            } else {
                log.appendLine("No parent PR found")
                removeDependentLabel(prNumber, workDir)
            }
        } else {
            log.appendLine("No dependency found")
            removeDependentLabel(prNumber, workDir)
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

    private fun removeDependentLabel(
        prNumber: Int,
        workDir: File
    ): CmdResult = runCmd(
        listOf(
            "gh",
            "pr",
            "edit",
            "$prNumber",
            "--remove-label",
            "Dependent"
        ), workDir
    )

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
    fun fetchPrs(
        repo: String,
        baseBranch: String?,
        author: String?,
        closed: Boolean = false
    ): List<PullRequest> {
        val args = mutableListOf(
            "gh",
            "pr",
            "list",
            "--repo",
            repo,
            "--state",
            if (closed) "closed" else "open",
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

    /** One PR review thread's state, as much as [fetchResolvableThreadIds] needs. [startedBy] is
     *  the author of the thread's first comment — i.e. whose conversation this is. */
    private data class ReviewThread(
        val id: String,
        val isResolved: Boolean,
        val isOutdated: Boolean,
        val startedBy: String?
    )

    /**
     * Review threads on a single PR, via GraphQL — `gh pr view`'s REST-backed `--json` fields
     * don't expose `isResolved`/`isOutdated` at the thread level, only the older-style flat
     * review comments, so this goes straight to the GraphQL API.
     */
    private fun fetchReviewThreads(owner: String, name: String, prNumber: Int): List<ReviewThread> {
        val query = """
            query(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!) {
              repository(owner: ${'$'}owner, name: ${'$'}name) {
                pullRequest(number: ${'$'}number) {
                  reviewThreads(first: 100) {
                    nodes {
                      id
                      isResolved
                      isOutdated
                      comments(first: 1) {
                        nodes { author { login } }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val result = runCmd(
            listOf(
                "gh", "api", "graphql",
                "-f", "query=$query",
                "-F", "owner=$owner",
                "-F", "name=$name",
                "-F", "number=$prNumber"
            )
        )
        if (result.exitCode != 0 || result.stdout.isBlank()) return emptyList()

        return try {
            val nodes = JsonParser.parseString(result.stdout).asJsonObject
                .getAsJsonObject("data")
                .getAsJsonObject("repository")
                .getAsJsonObject("pullRequest")
                .getAsJsonObject("reviewThreads")
                .getAsJsonArray("nodes")
            nodes.mapNotNull { n ->
                val obj = n.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val isResolved = obj.get("isResolved")?.asBoolean ?: false
                val isOutdated = obj.get("isOutdated")?.asBoolean ?: false
                val startedBy = obj.getAsJsonObject("comments")
                    ?.getAsJsonArray("nodes")
                    ?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("author")?.get("login")?.asString
                ReviewThread(id, isResolved, isOutdated, startedBy)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * For each PR in [numbers], the ids of review threads that are outdated, unresolved, and
     * were started by [currentUser] — the set the "Resolve comments" button offers to clear.
     * Fetched in parallel per PR, same pattern as [fetchMergeableStates].
     */
    fun fetchResolvableThreadIds(
        repo: String,
        numbers: List<Int>,
        currentUser: String
    ): Map<Int, List<String>> {
        if (numbers.isEmpty() || currentUser.isBlank()) return emptyMap()
        val parts = repo.split("/", limit = 2)
        if (parts.size != 2) return emptyMap()
        val (owner, name) = parts

        val results = ConcurrentHashMap<Int, List<String>>()
        val latch = CountDownLatch(numbers.size)
        numbers.forEach { num ->
            Thread {
                try {
                    val threads = fetchReviewThreads(owner, name, num)
                    results[num] = threads
                        .filter { !it.isResolved && it.isOutdated && it.startedBy == currentUser }
                        .map { it.id }
                } catch (e: Exception) {
                    results[num] = emptyList()
                } finally {
                    latch.countDown()
                }
            }.start()
        }
        latch.await(30, TimeUnit.SECONDS)
        return results
    }

    /** Resolves each of [threadIds] via the `resolveReviewThread` GraphQL mutation, returning how
     *  many succeeded. Partial failures don't fail the whole call — only reported as a failure if
     *  every single one errored out (e.g. `gh` itself unavailable). */
    fun resolveReviewThreads(threadIds: List<String>): Result<Int> {
        if (threadIds.isEmpty()) return Result.success(0)
        val mutation = """
            mutation(${'$'}threadId: ID!) {
              resolveReviewThread(input: {threadId: ${'$'}threadId}) {
                thread { id isResolved }
              }
            }
        """.trimIndent()

        var resolved = 0
        var lastError: String? = null
        threadIds.forEach { id ->
            val result = runCmd(
                listOf(
                    "gh",
                    "api",
                    "graphql",
                    "-f",
                    "query=$mutation",
                    "-f",
                    "threadId=$id"
                )
            )
            if (result.exitCode == 0) resolved++
            else lastError = result.stderr.ifBlank { result.stdout }.take(200)
        }

        return if (resolved == 0 && lastError != null) Result.failure(RuntimeException(lastError))
        else Result.success(resolved)
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

    fun existingRepoLabels(workDir: File): Set<String> {
        val result =
            runCmd(listOf("gh", "label", "list", "--json", "name", "--limit", "200"), workDir)
        if (result.exitCode != 0 || result.stdout.isBlank()) return emptySet()
        return try {
            JsonParser.parseString(result.stdout).asJsonArray
                .mapNotNull { it.asJsonObject.get("name")?.asString }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
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
