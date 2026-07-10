package com.khalibre.tools.devpanel.common

import com.intellij.openapi.project.Project
import java.io.File

/**
 * Small shared helper for resolving a project's git root and the `.git/cw` scratch
 * directory devpanel uses for caches and per-project UI state.
 */
object ProjectPaths {
    fun gitRoot(project: Project): File? {
        val base = project.basePath ?: return null
        var dir = File(base)
        while (dir.parentFile != null) {
            if (File(dir, ".git").isDirectory) return dir
            dir = dir.parentFile
        }
        return null
    }

    fun cwDir(project: Project): File? = gitRoot(project)?.let { File(it, ".git/cw") }
}
