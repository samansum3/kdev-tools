package com.khalibre.tools.devpanel.timelog

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.khalibre.tools.devpanel.common.ProjectPaths
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.time.LocalDate
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

class AddTimeDialog(
    private val project: Project,
    private val defaultDate: LocalDate,
    private val onLogged: () -> Unit
) : DialogWrapper(project, true) {

    private val cwDir = ProjectPaths.cwDir(project)

    private val issuePicker = IssuePickerButton()
    private val timeCombo = ComboBox(buildDurationOptions().toTypedArray())
    private val dateSpinner = JSpinner(SpinnerDateModel()).apply {
        editor = JSpinner.DateEditor(this, "yyyy-MM-dd")
        value = toDate(defaultDate)
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
        val topRow = JPanel(GridLayout(1, 3, 12, 0)).apply {
            add(labeled("Time code", issuePicker))
            add(labeled("Time", timeCombo))
            add(labeled("Date", dateSpinner))
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            preferredSize = Dimension(520, 260)
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
        ApplicationManager.getApplication().executeOnPooledThread {
            val tickets = TimeLogService.loadTimeTickets(dir)
            val rememberedQuery = TimeLogService.loadRememberedSearchQuery(dir)
            SwingUtilities.invokeLater {
                issuePicker.isEnabled = true
                issuePicker.setItems(tickets)
                issuePicker.setInitialSearchQuery(rememberedQuery)
                issuePicker.setOnSelected { updateOkEnabled() }
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
        val date = toLocalDate(dateSpinner.value as Date)
        val description = descriptionArea.text.trim()

        setBusy(true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = TimeLogService.addWorklog(ticket.key, timeSpent, date, description, cwDir)
            SwingUtilities.invokeLater {
                setBusy(false)
                result.onSuccess {
                    onLogged()
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
        dateSpinner.isEnabled = !busy
        descriptionArea.isEnabled = !busy
        okAction.putValue(Action.NAME, if (busy) "Adding…" else "Add")
    }

    companion object {
        private fun toDate(date: LocalDate): Date {
            val cal = Calendar.getInstance()
            cal.set(date.year, date.monthValue - 1, date.dayOfMonth, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.time
        }

        private fun toLocalDate(date: Date): LocalDate {
            val cal = Calendar.getInstance()
            cal.time = date
            return LocalDate.of(
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
            )
        }
    }
}
