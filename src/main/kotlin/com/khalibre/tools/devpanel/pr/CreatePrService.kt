package com.khalibre.tools.devpanel.pr

import com.intellij.openapi.project.Project
import com.khalibre.tools.devpanel.config.DevConfig
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
 *  5. If there's an image on the clipboard and a GH session token is configured, upload it
 *     (via the `gh image` command, same as the script) and embed it in the body.
 *  6. Push the branch if it has no upstream yet.
 *  7. `gh pr create`.
 *  8. Transition the Jira ticket to "PR Open".
 *
 * Must be called off the EDT — it does blocking process/network calls throughout.
 */
object CreatePrService {

    private val TICKET_KEY_RE = Regex("^[A-Z]+-[0-9]+(-[0-9]+)?$")
    private val CANONICAL_KEY_RE = Regex("^[A-Z]+-[0-9]+$")
    private val TRAILING_NUMBER_SUFFIX_RE = Regex("-[0-9]*$")
    private val IMAGE_URL_RE = Regex("""\((https://github\.com/user-attachments/assets/[^)]+)\)""")

    fun run(
        project: Project,
        draft: Boolean = false,
        baseBranchOverride: String? = null
    ): Result<CreatePrOutcome> {
        val workDir = project.basePath?.let { File(it) }
            ?: return Result.failure(RuntimeException("No project directory."))

        // ── Base branch ──────────────────────────────────────────────────
        val baseBranch = baseBranchOverride?.takeIf { it.isNotBlank() } ?: DevConfig.load().git.base_branch

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
        val summary = JiraService.fetchTicketSummary(ticketKey).getOrElse { return Result.failure(it) }

        // ── Parent branch / parent PR ───────────────────────────────────
        val parentPrRef = detectParentPrRef(workDir, branch)

        // ── Clipboard image ──────────────────────────────────────────────
        val clipboardImageMarkdown = tryUploadClipboardImage(workDir)

        // ── Title & body ─────────────────────────────────────────────────
        val prTitle = "$ticketKey: $summary"
        val jiraUrl = JiraService.ticketUrl(ticketKey)
        val prBody = buildString {
            if (parentPrRef != null) append("### DEPEND ON $parentPrRef\n")
            append(jiraUrl)
            if (clipboardImageMarkdown != null) append("\n").append(clipboardImageMarkdown)
        }

        // ── Push branch if it has no upstream ────────────────────────────
        val upstreamCheck =
            PrService.runCmd(listOf("git", "rev-parse", "--abbrev-ref", "@{upstream}"), workDir)
        if (upstreamCheck.exitCode != 0) {
            val push = PrService.runCmd(listOf("git", "push", "--set-upstream", "origin", branch), workDir)
            if (push.exitCode != 0)
                return Result.failure(RuntimeException(push.stderr.ifBlank { "git push failed" }.take(300)))
        }

        // ── gh pr create ────────────────────────────────────────────────
        val ghArgs = mutableListOf("gh", "pr", "create", "--title", prTitle, "--body", prBody)
        if (draft) ghArgs += "--draft"
        if (!baseBranch.isNullOrBlank()) ghArgs += listOf("--base", baseBranch)
        val createResult = PrService.runCmd(ghArgs, workDir)
        if (createResult.exitCode != 0)
            return Result.failure(
                RuntimeException(
                    createResult.stderr.ifBlank { createResult.stdout }.ifBlank { "gh pr create failed" }.take(300)
                )
            )
        val prUrl = createResult.stdout.lines().map { it.trim() }.lastOrNull { it.startsWith("http") }

        // ── Transition ticket status to "PR Open" ─────────────────────────
        val transitioned = JiraService.transitionTicket(ticketKey, "PR Open").isSuccess

        return Result.success(CreatePrOutcome(ticketKey, branch, prUrl, transitioned))
    }

    /**
     * Finds the nearest ancestor commit (excluding HEAD itself) that is also the tip of another
     * ticket-shaped branch, then looks up whether that branch has an open PR. Mirrors the
     * script's BRANCH_TIP_MAP / walk-the-log approach.
     */
    private fun detectParentPrRef(workDir: File, currentBranch: String): String? {
        val refsResult = PrService.runCmd(
            listOf("git", "for-each-ref", "--format=%(objectname) %(refname)", "refs/heads", "refs/remotes/origin"),
            workDir
        )
        if (refsResult.exitCode != 0) return null

        val tipMap = mutableMapOf<String, String>()
        refsResult.stdout.lines().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) return@forEach
            val hash = parts[0]
            val branchName = parts[1]
                .removePrefix("refs/remotes/origin/")
                .removePrefix("refs/heads/")
            if (TICKET_KEY_RE.matches(branchName) && branchName != currentBranch) {
                tipMap[hash] = branchName
            }
        }
        if (tipMap.isEmpty()) return null

        val logResult = PrService.runCmd(listOf("git", "log", "--pretty=format:%H", "HEAD"), workDir)
        if (logResult.exitCode != 0) return null
        val parentBranch = logResult.stdout.lines().drop(1) // skip HEAD's own commit
            .firstNotNullOfOrNull { tipMap[it] } ?: return null

        val prResult = PrService.runCmd(
            listOf("gh", "pr", "list", "--head", parentBranch, "--json", "number", "--jq", ".[0].number")
        )
        val num = prResult.stdout.trim()
        if (prResult.exitCode != 0 || num.isBlank() || num == "null") return null
        return "#$num"
    }

    private fun tryUploadClipboardImage(workDir: File): String? {
        val token = DevConfig.load().git.user_session.takeIf { it.isNotBlank() } ?: return null
        val uploaded = ClipboardImage.upload(token, workDir).getOrNull() ?: return null
        return "<img width=\"${uploaded.width}\" height=\"${uploaded.height}\" alt=\"image\" src=\"${uploaded.url}\" />"
    }
}
