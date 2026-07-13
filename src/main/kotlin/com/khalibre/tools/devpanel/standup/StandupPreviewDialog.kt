package com.khalibre.tools.devpanel.standup

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer

class StandupPreviewDialog(private val project: Project) : DialogWrapper(project, true) {

    private val previewArea = JBTextArea(18, 60).apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true
    }
    private var report: StandupReport? = null

    init {
        title = "Standup Preview"
        setOKButtonText("Send")
        init()
        isOKActionEnabled = false
        loadReport()
    }

    override fun createCenterPanel(): JComponent {
        previewArea.text = "Loading…"
        return JBScrollPane(previewArea).apply { preferredSize = Dimension(560, 420) }
    }

    private fun loadReport() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = StandupService.buildReport(project)
            SwingUtilities.invokeLater {
                result.onSuccess {
                    report = it
                    previewArea.text = StandupService.buildPlainPreview(it)
                    isOKActionEnabled = true
                }
                result.onFailure {
                    previewArea.text = "Failed to build standup report:\n${it.message}"
                }
            }
        }
    }

    override fun doOKAction() {
        val r = report ?: return
        setBusy(true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = StandupService.sendToTelegram(r)
            SwingUtilities.invokeLater {
                setBusy(false)
                result.onSuccess { super@StandupPreviewDialog.doOKAction() }
                result.onFailure { setErrorText(it.message ?: "Failed to send") }
            }
        }
    }

    private var spinTimer: Timer? = null
    private val spinIcons = listOf(
        AllIcons.Process.Step_1,
        AllIcons.Process.Step_2,
        AllIcons.Process.Step_3,
        AllIcons.Process.Step_4
    )

    private fun setBusy(busy: Boolean) {
        spinTimer?.stop(); spinTimer = null
        isOKActionEnabled = !busy && report != null
        if (busy) {
            var frame = 0
            okAction.putValue(Action.NAME, "Sending…")
            spinTimer = Timer(120) {
                okAction.putValue(Action.SMALL_ICON, spinIcons[frame++ % spinIcons.size])
            }.also { it.start() }
        } else {
            okAction.putValue(Action.NAME, "Send")
            okAction.putValue(Action.SMALL_ICON, null)
        }
    }
}
