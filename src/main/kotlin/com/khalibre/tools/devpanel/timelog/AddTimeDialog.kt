package com.khalibre.tools.devpanel.timelog

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.khalibre.tools.devpanel.common.ProjectPaths
import java.awt.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*
import javax.swing.*

/** Duration options offered in the Time combo: 15m increments up to 8h. */
private fun buildDurationOptions(): List<String> {
    val options = mutableListOf<String>()
    var totalMinutes = 15
    while (totalMinutes <= 8 * 60) {
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        options += when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
        totalMinutes += 15
    }
    return options
}

/** e.g. "Wednesday, July 22, 2026" */
private val DATE_OPTION_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)

class AddTimeDialog(
    private val project: Project,
    private val defaultDate: LocalDate,
    private val onLogged: (WorklogEntry) -> Unit
) : DialogWrapper(project, true) {

    private val cwDir = ProjectPaths.cwDir(project)

    private val issuePicker = IssuePickerButton()
    private val timeCombo = ComboBox(buildDurationOptions().toTypedArray())

    // Mon–Fri of the week containing defaultDate — the week currently shown in TimeLogPanel.
    private val weekDateOptions: List<LocalDate> = run {
        val monday = defaultDate.with(DayOfWeek.MONDAY)
        (0..4).map { monday.plusDays(it.toLong()) }
    }
    private val dateCombo = ComboBox(weekDateOptions.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.format(DATE_OPTION_FORMAT) }
        selectedItem = defaultDate
    }

    private val descriptionArea = JBTextArea(4, 30).apply { lineWrap = true; wrapStyleWord = true }

    private var busy = false

    init {
        title = "Add time"
        setOKButtonText("Add")
        init()
        isOKActionEnabled = false
        loadTimeTickets()
    }

    override fun createCenterPanel(): JComponent {
        val topRow = object : JPanel(GridBagLayout()) {
            override fun getMaximumSize(): Dimension =
                Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            val gc = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(0, 0, 0, 12)
            }

            gc.gridx = 0
            gc.weightx = 4.0
            add(labeled("Time code", issuePicker), gc)

            gc.gridx = 1
            gc.weightx = 2.0
            add(labeled("Time", timeCombo), gc)

            gc.gridx = 2
            gc.weightx = 3.0
            gc.insets = Insets(0, 0, 0, 0)
            add(labeled("Date", dateCombo), gc)
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            preferredSize = Dimension(520, 160)
            add(topRow)
            add(Box.createVerticalStrut(10))
            add(labeled("Description", JBScrollPane(descriptionArea)))
        }
    }

    private fun labeled(text: String, comp: JComponent): JComponent =
        JPanel(BorderLayout()).apply {
            add(JBLabel(text).apply { border = JBUI.Borders.emptyBottom(4) }, BorderLayout.NORTH)
            add(comp, BorderLayout.CENTER)
        }

    private fun loadTimeTickets() {
        val dir = cwDir ?: return
        issuePicker.text = "Loading…"
        issuePicker.isEnabled = false
        issuePicker.setOnSearchChanged { query ->
            ApplicationManager.getApplication().executeOnPooledThread {
                TimeLogService.rememberSearchQuery(dir, query)
            }
        }
        issuePicker.setOnSelected { updateOkEnabled() }

        ApplicationManager.getApplication().executeOnPooledThread {
            val tickets = TimeLogService.loadTimeTickets(dir)
            val rememberedQuery = TimeLogService.loadRememberedSearchQuery(dir)
            val rememberedTicketKey = TimeLogService.loadLastTicketKey(dir)
            val rememberedDuration = TimeLogService.loadLastDuration(dir)
            SwingUtilities.invokeLater {
                issuePicker.isEnabled = true
                issuePicker.setItems(tickets)
                issuePicker.setInitialSearchQuery(rememberedQuery)

                // Default to the last-used ticket/duration so repeated entries (e.g. a daily
                // stand-up log) don't require re-selecting the same thing every time.
                rememberedTicketKey?.let { key ->
                    tickets.firstOrNull { it.key == key }?.let { issuePicker.setSelected(it) }
                }
                if (rememberedDuration != null && rememberedDuration in buildDurationOptions()) {
                    timeCombo.selectedItem = rememberedDuration
                }

                updateOkEnabled()
            }
        }
    }

    private fun updateOkEnabled() {
        isOKActionEnabled = !busy && issuePicker.selectedTicket() != null
    }

    override fun doValidate(): ValidationInfo? {
        if (issuePicker.selectedTicket() == null)
            return ValidationInfo("Select a time code", issuePicker)
        return null
    }

    override fun doOKAction() {
        val validation = doValidate()
        if (validation != null) {
            setErrorText(validation.message, validation.component as? JComponent)
            return
        }

        val ticket = issuePicker.selectedTicket()!!
        val timeSpent = timeCombo.selectedItem as String
        val date = dateCombo.selectedItem as LocalDate
        val description = descriptionArea.text.trim()

        setBusy(true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = TimeLogService.addWorklog(ticket.key, timeSpent, date, description, cwDir)
            result.onSuccess {
                TimeLogService.rememberLastTicketKey(cwDir, ticket.key)
                TimeLogService.rememberLastDuration(cwDir, timeSpent)
            }
            SwingUtilities.invokeLater {
                setBusy(false)
                result.onSuccess { worklogId ->
                    onLogged(
                        WorklogEntry(
                            ticket.key,
                            worklogId,
                            ticket.summary,
                            date,
                            timeSpent,
                            description
                        )
                    )
                    super@AddTimeDialog.doOKAction()
                }.onFailure {
                    setErrorText(it.message ?: "Failed to add time")
                }
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        updateOkEnabled()
        issuePicker.isEnabled = !busy
        timeCombo.isEnabled = !busy
        dateCombo.isEnabled = !busy
        descriptionArea.isEnabled = !busy
        okAction.putValue(Action.NAME, if (busy) "Adding…" else "Add")
    }
}
