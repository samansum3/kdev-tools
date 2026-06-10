package com.khalibre.link2command.devpanel.pr

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.khalibre.link2command.devpanel.config.AuthorCache

object GitService {
    fun fetchUpstreamBranchNames(project: Project): List<String> {
        return try {
            val workDir = project.basePath?.let { java.io.File(it) }
            val result = PrService.runCmd(
                listOf("bash", "-c", "git branch -r | grep '^  upstream/'"),
                workDir
            )
            if (result.exitCode != 0) return emptyList()
            result.stdout.lines()
                .map { it.trim() }
                .filter { it.startsWith("upstream/") }
                .map { it.removePrefix("upstream/") }
                .filter { it.isNotBlank() && !it.contains("->") }
                .sorted()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun fetchPrAuthors(repo: String): List<AuthorCache.CachedAuthor> {
        return try {
            val result = PrService.runCmd(listOf(
                "gh", "api",
                "repos/$repo/collaborators",
                "--paginate",
                "--jq", ".[].login"
            ))
            val logins = if (result.exitCode == 0 && result.stdout.isNotBlank()) {
                result.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }.sorted()
            } else {
                // fallback: derive from open PRs
                val fallback = PrService.runCmd(listOf(
                    "gh", "pr", "list",
                    "--repo", repo,
                    "--state", "open",
                    "--limit", "100",
                    "--json", "author",
                    "--jq", "[.[].author.login] | unique | sort[]"
                ))
                if (fallback.exitCode != 0) return emptyList()
                fallback.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }
            }
            logins.map { login ->
                AuthorCache.CachedAuthor(login, "https://github.com/$login.png?size=16")
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
