package com.khalibre.link2command.devpanel.pr

import java.io.File

object GitService {

    fun fetchUpstreamBranchNames(workDir: File? = null): List<String> {
        return try {
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
}