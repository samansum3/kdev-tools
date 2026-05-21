package com.khalibre.link2command

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.openapi.project.Project

/**
 * Registers our filter so IntelliJ applies it to every console/terminal output.
 */
class TerminalFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> {
        return arrayOf(CommandFilter(project))
    }
}
