package com.khalibre.tools.devpanel.timelog

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.khalibre.tools.devpanel.common.CardUtils
import com.khalibre.tools.devpanel.common.ProjectPaths
import java.awt.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.swing.*
import javax.swing.border.CompoundBorder

class TimeLogPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val cwDir get() = ProjectPaths.cwDir(project)

    // Monday of the currently displayed week.
    private var weekStart: LocalDate = LocalDate.now().with(DayOfWeek.MONDAY)

    private val rangeLabel = JBLabel("", SwingConstants.CENTER).apply {
        font = font.deriveFont(font.size + 1f)
    }
    private val statusLabel = JBLabel("").apply {
        font = font.deriveFont(font.size - 1f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    private val daysGrid = JPanel(GridLayout(1, 5, 8, 0))

    init {
        border = JBUI.Borders.empty(10, 12)
        add(buildHeader(), BorderLayout.NORTH)

        val scroll = JBScrollPane(daysGrid).apply {
            border = JBUI.Borders.emptyTop(10)
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scroll, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        reload()
    }

    // ── Header: ◀  date range  ▶ ──────────────────────────────────────────────

    private fun buildHeader(): JComponent {
        val prevBtn = JButton("◀").apply {
            toolTipText = "Previous week"
            addActionListener { weekStart = weekStart.minusWeeks(1); reload() }
        }
        val nextBtn = JButton("▶").apply {
            toolTipText = "Next week"
            addActionListener { weekStart = weekStart.plusWeeks(1); reload() }
        }
        return JPanel(BorderLayout()).apply {
            add(prevBtn, BorderLayout.WEST)
            add(rangeLabel, BorderLayout.CENTER)
            add(nextBtn, BorderLayout.EAST)
        }
    }

    private fun updateRangeLabel() {
        val weekEnd = weekStart.plusDays(4)
        val fmt = DateTimeFormatter.ofPattern("MMM d")
        rangeLabel.text = "${weekStart.format(fmt)} – ${weekEnd.format(DateTimeFormatter.ofPattern("d, yyyy"))}"
    }

    // ── Reload ───────────────────────────────────────────────────────────────

    /** Called by AddTimeDialog after a successful add, and by the week-navigation buttons. */
    fun reload() {
        updateRangeLabel()
        val dir = cwDir
        if (dir == null) {
            statusLabel.text = "No git project detected"
            buildEmptyGrid()
            return
        }

        statusLabel.text = "Loading…"
        val weekEnd = weekStart.plusDays(4)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = TimeLogService.fetchWeekWorklogs(weekStart, weekEnd, dir)
            SwingUtilities.invokeLater {
                result.onSuccess { entries ->
                    statusLabel.text = ""
                    renderGrid(entries)
                }.onFailure {
                    statusLabel.text = "✘ ${it.message}"
                    buildEmptyGrid()
                }
            }
        }
    }

    private fun buildEmptyGrid() {
        daysGrid.removeAll()
        for (i in 0 until 5) {
            val date = weekStart.plusDays(i.toLong())
            daysGrid.add(buildDayColumn(date, emptyList()))
        }
        daysGrid.revalidate()
        daysGrid.repaint()
    }

    private fun renderGrid(entries: List<WorklogEntry>) {
        val byDate = entries.groupBy { it.date }
        daysGrid.removeAll()
        for (i in 0 until 5) {
            val date = weekStart.plusDays(i.toLong())
            daysGrid.add(buildDayColumn(date, byDate[date] ?: emptyList()))
        }
        daysGrid.revalidate()
        daysGrid.repaint()
    }

    // ── Day column ───────────────────────────────────────────────────────────

    private fun buildDayColumn(date: LocalDate, items: List<WorklogEntry>): JComponent {
        val totalMinutes = items.sumOf { parseDurationToMinutes(it.timeSpent) }
        val dayName = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(6)
            add(JBLabel("$dayName ${date.dayOfMonth}").apply {
                font = font.deriveFont(Font.BOLD, font.size - 1f)
            }, BorderLayout.WEST)
            add(JBLabel(formatMinutes(totalMinutes)).apply {
                font = font.deriveFont(font.size - 2f)
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            }, BorderLayout.EAST)
        }

        val cardsBox = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            if (items.isEmpty()) {
                add(JBLabel("No time logged").apply {
                    font = font.deriveFont(font.size - 2f)
                    foreground = JBUI.CurrentTheme.Label.disabledForeground()
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyBottom(8)
                })
            } else {
                items.forEach { entry ->
                    add(buildEntryCard(entry))
                    add(Box.createVerticalStrut(6))
                }
            }
            add(CardUtils.makeActionButton("+ Add time") {
                AddTimeDialog(project, date) { reload() }.show()
            }.apply { alignmentX = Component.LEFT_ALIGNMENT })
            add(Box.createVerticalGlue())
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(cardsBox).apply {
                border = JBUI.Borders.empty()
                verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
        }
    }

    private fun buildEntryCard(entry: WorklogEntry): JComponent {
        val card = object : JPanel() {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = CompoundBorder(
                BorderFactory.createLineBorder(
                    JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1, true
                ),
                JBUI.Borders.empty(6, 8)
            )
        }
        val top = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel(entry.issueKey).apply {
                font = font.deriveFont(Font.BOLD, font.size - 1f)
            }, BorderLayout.WEST)
            add(JBLabel(entry.timeSpent).apply {
                font = font.deriveFont(font.size - 2f)
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            }, BorderLayout.EAST)
        }
        val summaryText = entry.comment.ifBlank { entry.issueSummary }
        val summary = JBLabel("<html>${escapeHtml(summaryText)}</html>").apply {
            font = font.deriveFont(font.size - 2f)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            alignmentX = Component.LEFT_ALIGNMENT
        }
        card.add(top)
        card.add(summary)
        return card
    }

    private fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun parseDurationToMinutes(timeSpent: String): Int {
        var minutes = 0
        Regex("(\\d+)([hHmMdD])").findAll(timeSpent).forEach { m ->
            val value = m.groupValues[1].toIntOrNull() ?: 0
            when (m.groupValues[2].lowercase()) {
                "h" -> minutes += value * 60
                "m" -> minutes += value
                "d" -> minutes += value * 8 * 60 // Jira's default "1 day = 8 hours"
            }
        }
        return minutes
    }

    private fun formatMinutes(totalMinutes: Int): String {
        if (totalMinutes == 0) return "0h"
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }
}
