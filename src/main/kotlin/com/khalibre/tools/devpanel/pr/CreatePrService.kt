package com.khalibre.tools.devpanel.pr

import com.intellij.openapi.project.Project
import com.khalibre.tools.devpanel.config.DevConfig
import com.khalibre.tools.devpanel.pr.CreatePrService.collectExtraTicketKeys
import com.khalibre.tools.devpanel.pr.CreatePrService.collectOwnCommitHashes
import com.khalibre.tools.devpanel.pr.CreatePrService.tagsFromChangedFiles
import com.khalibre.tools.devpanel.tickets.JiraService
import java.io.File

/** Result of a successful [CreatePrService.run] call. */
data class CreatePrOutcome(
    val ticketKey: String,
    val branch: String,
    val prUrl: String?,
    val statusTransitioned: Boolean
)

/**
 * Kotlin port of the `create-pr` shell command (see create-pr.sh) so the same workflow can
 * run from inside the plugin instead of a terminal. Mirrors the script step for step:
 *
 *  1. Resolve base branch (falls back to the `base-branch` command, same as the script).
 *  2. Read the current branch and validate/derive the Jira ticket key from it.
 *  3. Fetch the ticket's summary from Jira (via acli) to build the PR title.
 *  4. Detect a parent branch (another ticket branch reachable in this branch's history) and,
 *     if it has an open PR, add a "### DEPEND ON #N" line to the body.
 *  5. Scan this branch's own commits for other tickets' Jira links (e.g. work folded in from
 *     another ticket) and fold their keys into the title: "CW-100, CW-101: summary".
 *  6. If there's an image on the clipboard and a GH session token is configured, upload it
 *     (via the `gh image` command, same as the script) and embed it in the body.
 *  7. Push the branch if it has no upstream yet.
 *  8. `gh pr create`.
 *  9. Transition the Jira ticket to "PR Open".
 *
 * Must be called off the EDT — it does blocking process/network calls throughout.
 */
object CreatePrService {

    internal val TICKET_KEY_RE = Regex("^[A-Z]+-[0-9]+(-[0-9]+)?$")
    private val CANONICAL_KEY_RE = Regex("^[A-Z]+-[0-9]+$")
    private val TRAILING_NUMBER_SUFFIX_RE = Regex("-[0-9]*$")
    private val IMAGE_URL_RE = Regex("""\((https://github\.com/user-attachments/assets/[^)]+)\)""")
    private val JIRA_BROWSE_URL_RE = Regex("""/browse/([A-Z]+-[0-9]+)""")

    fun run(
        project: Project,
        draft: Boolean = false,
        baseBranchOverride: String? = null
    ): Result<CreatePrOutcome> {
        val workDir = project.basePath?.let { File(it) }
            ?: return Result.failure(RuntimeException("No project directory."))
        val cwDir = com.khalibre.tools.devpanel.common.ProjectPaths.cwDir(project)

        // ── Base branch ──────────────────────────────────────────────────
        val baseBranch =
            baseBranchOverride?.takeIf { it.isNotBlank() } ?: DevConfig.load(cwDir).git.base_branch

        // ── Current branch ───────────────────────────────────────────────
        val branchResult =
            PrService.runCmd(listOf("git", "rev-parse", "--abbrev-ref", "HEAD"), workDir)
        if (branchResult.exitCode != 0)
            return Result.failure(RuntimeException("Not inside a git repository."))
        val branch = branchResult.stdout.trim()
        if (branch == "HEAD")
            return Result.failure(RuntimeException("You are in a detached HEAD state. Check out a branch first."))
        if (!TICKET_KEY_RE.matches(branch))
            return Result.failure(
                RuntimeException("Branch name '$branch' doesn't look like a Jira ticket key (e.g. CW-123 or CW-123-2).")
            )

        // Strip trailing -<number> suffix to get the canonical Jira ticket key; if stripping
        // went too far (e.g. removed the issue number), fall back to the raw branch name.
        var ticketKey = branch.replace(TRAILING_NUMBER_SUFFIX_RE, "")
        if (!CANONICAL_KEY_RE.matches(ticketKey)) ticketKey = branch

        // ── Jira ticket details (summary + issue type, one request) ──────
        val ticketDetails =
            JiraService.fetchTicketDetails(ticketKey).getOrElse { return Result.failure(it) }
        val summary = ticketDetails.summary

        // ── Parent branch / parent PR ───────────────────────────────────
        val parentPrRef = detectParentPrRef(workDir, branch)

        // ── Extra ticket keys pulled in from this branch's own commits ────
        val extraTicketKeys = collectExtraTicketKeys(workDir, branch, ticketKey)

        // ── Clipboard image ──────────────────────────────────────────────
        val clipboardImageMarkdown = tryUploadClipboardImage(workDir, cwDir)

        // ── Title & body ─────────────────────────────────────────────────
        val allTicketKeys = listOf(ticketKey) + extraTicketKeys
        val titleKeys = allTicketKeys.joinToString(", ")
        val prTitle = "$titleKeys: $summary"
        val jiraUrls = allTicketKeys.joinToString("\n") { JiraService.ticketUrl(it, cwDir) }
        val prBody = buildString {
            if (parentPrRef != null) append("### DEPEND ON $parentPrRef\n")
            append(jiraUrls)
            if (clipboardImageMarkdown != null) append("\n").append(clipboardImageMarkdown)
        }

        // ── Push branch if it has no upstream ────────────────────────────
        val upstreamCheck =
            PrService.runCmd(listOf("git", "rev-parse", "--abbrev-ref", "@{upstream}"), workDir)
        if (upstreamCheck.exitCode != 0) {
            val push =
                PrService.runCmd(listOf("git", "push", "--set-upstream", "origin", branch), workDir)
            if (push.exitCode != 0)
                return Result.failure(RuntimeException(push.stderr.ifBlank { "git push failed" }
                    .take(300)))
        }

        // ── gh pr create ────────────────────────────────────────────────
        val ghArgs = mutableListOf("gh", "pr", "create", "--title", prTitle, "--body", prBody)
        if (draft) ghArgs += "--draft"
        if (!baseBranch.isNullOrBlank()) ghArgs += listOf("--base", baseBranch)

        val prTags = computePrTags(workDir, branch, ticketDetails.issueType, parentPrRef)
        if (prTags.isNotEmpty()) ghArgs += listOf("--label", prTags.joinToString(","))

        val defaultReviewers = DevConfig.load(cwDir).git.default_reviewers
        if (defaultReviewers.isNotEmpty()) {
            val currentUser = PrService.currentGhUser()
            val reviewers = defaultReviewers.filter { it != currentUser }
            if (reviewers.isNotEmpty())
                ghArgs += listOf("--reviewer", reviewers.joinToString(","))
        }

        val createResult = PrService.runCmd(ghArgs, workDir)
        if (createResult.exitCode != 0)
            return Result.failure(
                RuntimeException(
                    createResult.stderr.ifBlank { createResult.stdout }
                        .ifBlank { "gh pr create failed" }.take(300)
                )
            )
        val prUrl =
            createResult.stdout.lines().map { it.trim() }.lastOrNull { it.startsWith("http") }

        // ── Transition ticket status to "PR Open" ─────────────────────────
        val transitioned = JiraService.transitionTicket(ticketKey, "PR Open").isSuccess

        return Result.success(CreatePrOutcome(ticketKey, branch, prUrl, transitioned))
    }

    /** Maps commit hash -> every branch name (local, or on any remote) pointing at that commit,
     *  other than [excludeBranch] itself. Unlike a ticket-key-only map, this includes ordinary
     *  branches like "dev-wf-s9" too — needed so [collectExtraTicketKeys] can recognize *any*
     *  branch tip it walks past, not just ticket-shaped ones. Scans every remote (not just
     *  "origin"), since a base branch commonly lives on "upstream" instead. */
    private fun buildBranchTipMap(workDir: File, excludeBranch: String): Map<String, List<String>> {
        val refsResult = PrService.runCmd(
            listOf(
                "git",
                "for-each-ref",
                "--format=%(objectname) %(refname)",
                "refs/heads",
                "refs/remotes"
            ),
            workDir
        )
        if (refsResult.exitCode != 0) return emptyMap()

        val tipMap = mutableMapOf<String, MutableList<String>>()
        refsResult.stdout.lines().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) return@forEach
            val hash = parts[0]
            val refname = parts[1]
            val branchName = when {
                refname.startsWith("refs/heads/") -> refname.removePrefix("refs/heads/")
                refname.startsWith("refs/remotes/") ->
                    refname.removePrefix("refs/remotes/").substringAfter('/', "")

                else -> ""
            }
            if (branchName.isNotBlank() && branchName != excludeBranch) {
                tipMap.getOrPut(hash) { mutableListOf() }.add(branchName)
            }
        }
        return tipMap
    }

    /** The open PR number whose head is [branchName], or null if there isn't one. */
    private fun getOpenPrNumber(workDir: File, branchName: String): String? {
        val prResult = PrService.runCmd(
            listOf(
                "gh",
                "pr",
                "list",
                "--head",
                branchName,
                "--json",
                "number",
                "--jq",
                ".[0].number"
            ),
            workDir
        )
        val num = prResult.stdout.trim()
        return num.takeIf { prResult.exitCode == 0 && it.isNotBlank() && it != "null" }
    }

    /** True if `gh` reports an open PR whose head is [branchName]. */
    private fun hasOpenPr(workDir: File, branchName: String): Boolean =
        getOpenPrNumber(workDir, branchName) != null

    /**
     * Walks [logRef]'s own `--first-parent` history (excluding its own tip commit) one commit at
     * a time, same boundary rules as [collectExtraTicketKeys]:
     *
     *  - Hits the tip of another ticket-shaped branch (e.g. "CW-100")? If it has an open PR,
     *    that's the parent to report. If it doesn't (a stale/never-opened branch ref), keep
     *    walking past it and check the next branch tip encountered.
     *  - Hits the tip of any other branch (e.g. "dev-wf-s9", a base/integration branch) — stop
     *    immediately with no parent: this branch isn't stacked on anything, it's just based
     *    directly on the base branch.
     *
     * [logRef] should be the branch actually being inspected — not necessarily `HEAD`, since the
     * caller (e.g. rebasing a branch other than the one currently checked out) may be inspecting
     * a branch that isn't checked out at all.
     */
    internal fun detectParentPrRef(
        workDir: File,
        currentBranch: String,
        logRef: String = "HEAD"
    ): String? {
        val tipMap = buildBranchTipMap(workDir, currentBranch)

        val logResult =
            PrService.runCmd(
                listOf("git", "log", "--first-parent", "--pretty=format:%H", logRef),
                workDir
            )
        if (logResult.exitCode != 0) return null

        for (hash in logResult.stdout.lines().drop(1)
            .filter { it.isNotBlank() }) { // skip logRef's own commit
            val namesHere = tipMap[hash] ?: continue
            for (name in namesHere) {
                if (!TICKET_KEY_RE.matches(name)) return null // base/integration branch — no parent.
                val num = getOpenPrNumber(workDir, name)
                if (num != null) return "#$num" // stacked on a real, already-open PR.
                // No PR yet: not a real boundary — keep walking past it.
            }
        }
        return null
    }

    /**
     * Walks HEAD's `--first-parent` history and returns just the commit hashes that belong to
     * this branch's own work, applying the same boundary rule used elsewhere in this file:
     *
     *  - Hits the tip of another ticket-shaped branch (e.g. "CW-100")? If it has an open PR,
     *    that's a real stacking boundary — stop there. If it doesn't (a stale/never-opened
     *    branch ref), its commits are effectively folded into *this* PR, so keep walking past it.
     *  - Hits the tip of any other branch (e.g. "dev-wf-s9", a base/integration branch) — stop
     *    immediately, no PR check needed.
     *
     * Returned newest-first, matching `git log`'s natural order.
     */
    private fun collectOwnCommitHashes(workDir: File, branch: String): List<String> {
        val tipMap = buildBranchTipMap(workDir, branch)

        val logResult =
            PrService.runCmd(
                listOf("git", "log", "--first-parent", "--pretty=format:%H", "HEAD"),
                workDir
            )
        if (logResult.exitCode != 0) return emptyList()

        val prCheckCache = mutableMapOf<String, Boolean>()
        val ownHashes = mutableListOf<String>()
        outer@ for (hash in logResult.stdout.lines().filter { it.isNotBlank() }) {
            val namesHere = tipMap[hash]
            if (namesHere != null) {
                for (name in namesHere) {
                    val isTicketShaped = TICKET_KEY_RE.matches(name)
                    if (!isTicketShaped) break@outer // base/integration branch — stop, no check.
                    val hasPr = prCheckCache.getOrPut(name) { hasOpenPr(workDir, name) }
                    if (hasPr) break@outer // stacked on a real, already-open PR — stop here.
                    // No PR yet: this ticket branch's tip doesn't count as a boundary; fall
                    // through and keep walking past it toward the next branch tip.
                }
            }
            ownHashes += hash
        }
        return ownHashes
    }

    /**
     * File paths touched across this branch's own commits (same boundary rules as
     * [collectOwnCommitHashes]) — used to infer PR tags from the kinds of files changed (see
     * [tagsFromChangedFiles]). A single `git diff --name-only` between the parent of the oldest
     * own commit and HEAD, rather than per-commit diffs, since [collectOwnCommitHashes] returns a
     * contiguous `--first-parent` range.
     */
    private fun collectChangedFiles(workDir: File, branch: String): List<String> {
        val ownHashes = collectOwnCommitHashes(workDir, branch)
        if (ownHashes.isEmpty()) return emptyList()
        val oldest = ownHashes.last()

        val parentResult = PrService.runCmd(listOf("git", "rev-parse", "$oldest^"), workDir)
        val diffResult = if (parentResult.exitCode == 0) {
            PrService.runCmd(
                listOf("git", "diff", "--name-only", parentResult.stdout.trim(), "HEAD"),
                workDir
            )
        } else {
            // Oldest own commit is the repo root commit (no parent) — diff its tree directly.
            PrService.runCmd(
                listOf("git", "show", "--name-only", "--pretty=format:", oldest),
                workDir
            )
        }
        if (diffResult.exitCode != 0) return emptyList()
        return diffResult.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun tagsFromChangedFiles(files: List<String>): List<String> {
        fun hasExt(vararg exts: String) = files.any { f -> exts.any { f.endsWith(".$it") } }
        val tags = mutableListOf<String>()
        if (hasExt("java", "kt")) tags += "BE"
        if (hasExt("ts", "js", "tsx")) tags += "FE"
        if (hasExt("vue", "css", "scss", "html", "jsp", "jspf")) tags += "UI"
        return tags
    }

    private fun computePrTags(
        workDir: File,
        branch: String,
        mainTicketIssueType: String?,
        parentPrRef: String?
    ): List<String> {
        val fileTags = tagsFromChangedFiles(collectChangedFiles(workDir, branch))
        val bugTag = mainTicketIssueType
            ?.takeIf {
                it.equals("Bug", ignoreCase = true) || it.equals("Defect", ignoreCase = true)
            }
            ?.let { "Bug" }
        val dependentTag = if (parentPrRef != null) "Dependent" else null

        val allTags = (fileTags + listOfNotNull(bugTag, dependentTag)).distinct()
        val availableLabels = PrService.existingRepoLabels(workDir)
        return allTags.filter { it in availableLabels }
    }

    /**
     * Extracts Jira ticket keys referenced by [ticketKey]'s own commits (one per commit-message
     * line, matching the `.../browse/KEY` convention), scanning the same commit range as
     * [collectOwnCommitHashes]. Returns the distinct keys found other than [ticketKey] itself,
     * oldest commit first, so a PR that folds in work from another ticket gets a title like
     * "CW-100, CW-101: summary" instead of just "CW-100".
     */
    private fun collectExtraTicketKeys(
        workDir: File,
        branch: String,
        ticketKey: String
    ): List<String> {
        val ownHashes = collectOwnCommitHashes(workDir, branch)
        if (ownHashes.isEmpty()) return emptyList()

        // Re-walk oldest -> newest so extra keys come out in the order the work happened.
        val found = LinkedHashSet<String>()
        ownHashes.asReversed().forEach { hash ->
            val msgResult =
                PrService.runCmd(listOf("git", "log", "-1", "--pretty=format:%B", hash), workDir)
            if (msgResult.exitCode != 0) return@forEach
            msgResult.stdout.lines().forEach { line ->
                JIRA_BROWSE_URL_RE.find(line)?.groupValues?.get(1)?.let { key ->
                    if (key != ticketKey) found += key
                }
            }
        }
        return found.toList()
    }

    private fun tryUploadClipboardImage(workDir: File, cwDir: File?): String? {
        val token = DevConfig.load(cwDir).git.user_session.takeIf { it.isNotBlank() } ?: return null
        val uploaded = ClipboardImage.upload(token, workDir).getOrNull() ?: return null
        return "<img width=\"${uploaded.width}\" height=\"${uploaded.height}\" alt=\"image\" src=\"${uploaded.url}\" />"
    }
}
