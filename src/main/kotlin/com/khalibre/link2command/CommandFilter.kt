package com.khalibre.link2command

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory

/**
 * Scans terminal output for [run: <command>] and makes it clickable.
 * Clicking executes the command in the active terminal tab.
 *
 * Examples:
 *   [run: new CW-36237]
 *   [run: merge-pr 29645]
 *   [run: subtasks CW-123 --hide-done]
 */
class CommandFilter(private val project: Project) : Filter {

    private val pattern = Regex("""\[r:\s*([^\]]+)\]""")

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val matches = pattern.findAll(line).toList()
        if (matches.isEmpty()) return null

        val lineStart = entireLength - line.length
        val resultItems = matches.map { match ->
            val linkStart = lineStart + match.range.first
            val linkEnd = lineStart + match.range.last + 1
            val command = match.groupValues[1].trim()
            Filter.ResultItem(linkStart, linkEnd, RunCommandHyperlinkInfo(project, command))
        }

        return Filter.Result(resultItems)
    }
}

class RunCommandHyperlinkInfo(
    private val project: Project,
    private val command: String
) : HyperlinkInfo {

    override fun navigate(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            // Activate the Terminal tool window first
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
                ?: return@invokeLater

            toolWindow.activate {
                val tabsManager = TerminalToolWindowTabsManager.getInstance(project)

                // Get currently selected content/tab
                val selectedContent = toolWindow.contentManager.selectedContent
                    ?: return@activate

                // Find matching terminal tab
                val tabInfo = tabsManager.tabs.firstOrNull {
                    it.content == selectedContent
                } ?: return@activate

                val view = tabInfo.view

                // Build and send the command with execute (adds Enter)
                // Focus the terminal component — this brings it to front and scrolls to bottom
                view.component.requestFocusInWindow()
                view.createSendTextBuilder().shouldExecute().send(command)
            }
        }
    }
}
