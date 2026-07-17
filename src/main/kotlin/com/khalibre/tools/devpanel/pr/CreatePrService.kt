package com.khalibre.tools.devpanel.pr

import com.intellij.openapi.project.Project
import com.khalibre.tools.devpanel.config.DevConfig
import com.khalibre.tools.devpanel.pr.CreatePrService.collectExtraTicketKeys
import com.khalibre.tools.devpanel.pr.CreatePrService.detectParentPrRef
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

    private val TICKET_KEY_RE = Regex("^[A-Z]+-[0-9]+(-[0-9]+)?$")
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

        // ── Jira summary ─────────────────────────────────────────────────
        val summary =
            JiraService.fetchTicketSummary(ticketKey).getOrElse { return Result.failure(it) }

        // ── Parent branch / parent PR ───────────────────────────────────
        val parentPrRef = detectParentPrRef(workDir, branch)

        // ── Extra ticket keys pulled in from this branch's own commits ────
        val extraTicketKeys = collectExtraTicketKeys(workDir, branch, ticketKey, baseBranch)

        // ── Clipboard image ──────────────────────────────────────────────
        val clipboardImageMarkdown = tryUploadClipboardImage(workDir, cwDir)

        // ── Title & body ─────────────────────────────────────────────────
        val titleKeys = (listOf(ticketKey) + extraTicketKeys).joinToString(", ")
        val prTitle = "$titleKeys: $summary"
        val jiraUrl = JiraService.ticketUrl(ticketKey, cwDir)
        val prBody = buildString {
            if (parentPrRef != null) append("### DEPEND ON $parentPrRef\n")
            append(jiraUrl)
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

    /** Maps commit hash -> branch name for every locally-known branch (local or origin) whose
     *  name looks like a Jira ticket key, other than [excludeBranch] itself. Shared by
     *  [detectParentPrRef] (find what this branch is stacked on) and [collectExtraTicketKeys]
     *  (know where to stop walking this branch's own commit log). */
    private fun buildTicketBranchTipMap(workDir: File, excludeBranch: String): Map<String, String> {
        val refsResult = PrService.runCmd(
            listOf(
                "git",
                "for-each-ref",
                "--format=%(objectname) %(refname)",
                "refs/heads",
                "refs/remotes/origin"
            ),
            workDir
        )
        if (refsResult.exitCode != 0) return emptyMap()

        val tipMap = mutableMapOf<String, String>()
        refsResult.stdout.lines().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) return@forEach
            val hash = parts[0]
            val branchName = parts[1]
                .removePrefix("refs/remotes/origin/")
                .removePrefix("refs/heads/")
            if (TICKET_KEY_RE.matches(branchName) && branchName != excludeBranch) {
                tipMap[hash] = branchName
            }
        }
        return tipMap
    }

    /**
     * Finds the nearest ancestor commit (excluding [logRef]'s own tip) that is also the tip of
     * another ticket-shaped branch, then looks up whether that branch has an open PR. Mirrors the
     * script's BRANCH_TIP_MAP / walk-the-log approach.
     *
     * [logRef] defaults to `HEAD` (the create-pr case: walking the currently checked-out branch).
     * [PrService.updatePr] passes an explicit `origin/<branch>` ref instead, since the PR being
     * updated isn't necessarily the branch that's currently checked out locally.
     */
    internal fun detectParentPrRef(
        workDir: File,
        currentBranch: String,
        logRef: String = "HEAD"
    ): String? {
        val tipMap = buildTicketBranchTipMap(workDir, currentBranch)
        if (tipMap.isEmpty()) return null

        val logResult =
            PrService.runCmd(listOf("git", "log", "--pretty=format:%H", logRef), workDir)
        if (logResult.exitCode != 0) return null
        val parentBranch = logResult.stdout.lines().drop(1) // skip logRef's own commit
            .firstNotNullOfOrNull { tipMap[it] } ?: return null

        val prResult = PrService.runCmd(
            listOf(
                "gh",
                "pr",
                "list",
                "--head",
                parentBranch,
                "--json",
                "number",
                "--jq",
                ".[0].number"
            ),
            workDir
        )
        val num = prResult.stdout.trim()
        if (prResult.exitCode != 0 || num.isBlank() || num == "null") return null
        return "#$num"
    }

    /**
     * Scans this branch's own commit messages — walking `--first-parent` from HEAD so a merge of
     * the base branch back into this one doesn't drag in unrelated history — for Jira ticket
     * links, one per line (matching the commit-message convention of a bare `.../browse/KEY` line
     * per referenced ticket). Stops at whichever boundary comes first: the tip of another ticket
     * branch this one is stacked on, or the merge-base with [baseBranch]. Returns the distinct
     * keys found other than [ticketKey] itself, oldest commit first, so a PR that folds in work
     * from another ticket gets a title like "CW-100, CW-101: summary" instead of just "CW-100".
     */
    private fun collectExtraTicketKeys(
        workDir: File,
        branch: String,
        ticketKey: String,
        baseBranch: String?
    ): List<String> {
        val tipMap = buildTicketBranchTipMap(workDir, branch)

        val mergeBaseHash = baseBranch?.takeIf { it.isNotBlank() }?.let { base ->
            val local = PrService.runCmd(listOf("git", "merge-base", "HEAD", base), workDir)
            if (local.exitCode == 0 && local.stdout.isNotBlank()) local.stdout.trim()
            else {
                // Base branches aren't necessarily on "origin" — e.g. a base branch like
                // "dev-wf-s9" lives on "upstream" per the same remote convention PrService uses
                // elsewhere (ticket-shaped branch names → origin, everything else → upstream).
                // Falling back to a hardcoded "origin/$base" here meant the merge-base lookup
                // silently failed for such base branches, leaving this walk with no boundary
                // there — so it kept walking back through the base branch's own history,
                // collecting unrelated ticket keys, until it happened to hit another ticket
                // branch's tip.
                val remoteName = PrService.getRemote(base)
                val remote =
                    PrService.runCmd(
                        listOf("git", "merge-base", "HEAD", "$remoteName/$base"),
                        workDir
                    )
                if (remote.exitCode == 0 && remote.stdout.isNotBlank()) remote.stdout.trim() else null
            }
        }

        val logResult =
            PrService.runCmd(
                listOf("git", "log", "--first-parent", "--pretty=format:%H", "HEAD"),
                workDir
            )
        if (logResult.exitCode != 0) return emptyList()

        // Walk newest -> oldest, stopping at whichever boundary is nearer to HEAD.
        val ownHashes = mutableListOf<String>()
        for (hash in logResult.stdout.lines().filter { it.isNotBlank() }) {
            if (hash == mergeBaseHash) break
            if (tipMap.containsKey(hash)) break
            ownHashes += hash
        }
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
