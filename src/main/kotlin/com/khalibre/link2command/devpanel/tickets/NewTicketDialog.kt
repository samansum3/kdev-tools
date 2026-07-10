package com.khalibre.link2command.devpanel.tickets

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.util.ui.JBUI
import com.khalibre.link2command.devpanel.common.ProjectPaths
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.io.File
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * "New ticket" dialog for a Tickets sub-tab. Creates either a top-level ticket (Parent blank)
 * or a subtask (Parent set), via [JiraService.createTicket].
 *
 * Remembers the last-used Parent/Type *per sub-tab* under
 * `.git/cw/tabs/<tabId>/new-ticket-parent` / `new-ticket-type` — mirroring how that tab's own
 * "parent tickets" filter is persisted. If nothing's remembered yet and the tab's "parent
 * tickets" filter currently holds exactly one key, that's used as the initial Parent value.
 */
class NewTicketDialog(
    private val project: Project,
    private val tabId: String
) : DialogWrapper(project, true) {

    private val cwDir: File? = ProjectPaths.cwDir(project)
    private val tabDir: File? = cwDir?.let { TicketTabsStore.tabStateDir(it, tabId) }

    private val clearParentExtension = ExtendableTextComponent.Extension.create(
        AllIcons.Actions.Close, AllIcons.Actions.CloseHovered, "Clear parent ticket"
    ) { clearParent() }

    private val parentField = ExtendableTextField().apply { toolTipText = "e.g. CW-123" }
    private val typeCombo = ComboBox<String>()
    private val summaryField = JBTextField()
    private val descriptionArea = JBTextArea(6, 40).apply { lineWrap = true; wrapStyleWord = true }

    // Prefetched once so toggling Parent blank <-> non-blank swaps the combo instantly
    // instead of re-hitting Jira on every keystroke.
    private var regularTypeNames: List<String> = emptyList()
    private var subtaskTypeIdByName: Map<String, String> = emptyMap()
    private var metaLoaded = false
    private var rememberedTypeName: String? = null

    init {
        title = "New Ticket"
        setOKButtonText("Create")
        init()
        loadRememberedDefaults()
        loadTypeMeta()

        parentField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onParentChanged()
            override fun removeUpdate(e: DocumentEvent) = onParentChanged()
            override fun changedUpdate(e: DocumentEvent) = onParentChanged()
        })
    }

    override fun createCenterPanel(): JComponent {
        val topRow = JPanel(GridLayout(1, 2, 12, 0)).apply {
            add(labeledField("Parent ticket", parentField))
            add(labeledField("Type", typeCombo))
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            preferredSize = Dimension(480, 320)
            add(topRow)
            add(Box.createVerticalStrut(10))
            add(labeledField("Summary", summaryField))
            add(Box.createVerticalStrut(10))
            add(labeledField("Description", JBScrollPane(descriptionArea)))
        }
    }

    private fun labeledField(label: String, field: JComponent): JComponent =
        JPanel(BorderLayout()).apply {
            add(JBLabel(label).apply { border = JBUI.Borders.emptyBottom(4) }, BorderLayout.NORTH)
            add(field, BorderLayout.CENTER)
        }

    override fun getPreferredFocusedComponent(): JComponent = parentField

    // ── Parent field: clear button + reactive type-list switch ─────────────

    private fun clearParent() {
        parentField.text = ""
        tabDir?.let { File(it, "new-ticket-parent").delete() }
    }

    private fun updateParentClearIcon() {
        val has = parentField.text.isNotBlank()
        val hasExt = parentField.extensions.contains(clearParentExtension)
        if (has && !hasExt) parentField.addExtension(clearParentExtension)
        else if (!has && hasExt) parentField.removeExtension(clearParentExtension)
    }

    private fun onParentChanged() {
        updateParentClearIcon()
        rebuildTypeCombo()
    }

    // ── Remembered defaults ──────────────────────────────────────────────────

    private fun loadRememberedDefaults() {
        val dir = tabDir
        val rememberedParent = dir?.let { File(it, "new-ticket-parent") }
            ?.takeIf { it.exists() }?.readText()?.trim()

        if (!rememberedParent.isNullOrBlank()) {
            parentField.text = rememberedParent
        } else {
            // Nothing remembered — fall back to the tab's own "parent tickets" filter,
            // but only when it's unambiguous (exactly one key).
            val keys = dir?.let { File(it, "parent-tickets") }?.takeIf { it.exists() }
                ?.readLines()?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            if (keys.size == 1) parentField.text = keys.first()
        }
        updateParentClearIcon()

        rememberedTypeName = dir?.let { File(it, "new-ticket-type") }
            ?.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotBlank() }
    }

    // ── Type combo ───────────────────────────────────────────────────────────

    private fun loadTypeMeta() {
        val dir = cwDir
        if (dir == null) {
            typeCombo.addItem("(no project configured)"); typeCombo.isEnabled = false
            return
        }
        typeCombo.isEnabled = false
        typeCombo.removeAllItems()
        typeCombo.addItem("Loading…")

        ApplicationManager.getApplication().executeOnPooledThread {
            JiraMetaCache.load(dir)
            val regular = JiraMetaCache.current().types.map { it.name }
                .ifEmpty { JiraMetaService.loadTypes(dir).map { it.name } }
            val subtasks = JiraMetaService.loadSubtaskTypes(dir)
            SwingUtilities.invokeLater {
                regularTypeNames = regular
                subtaskTypeIdByName = subtasks.associate { it.name to it.id }
                metaLoaded = true
                rebuildTypeCombo()
            }
        }
    }

    private fun rebuildTypeCombo() {
        if (!metaLoaded) return
        val hasParent = parentField.text.isNotBlank()
        val names = if (hasParent) subtaskTypeIdByName.keys.toList() else regularTypeNames
        val previouslySelected = typeCombo.selectedItem as? String

        typeCombo.removeAllItems()
        names.forEach { typeCombo.addItem(it) }
        typeCombo.isEnabled = names.isNotEmpty()

        typeCombo.selectedItem = when {
            previouslySelected != null && previouslySelected in names -> previouslySelected
            rememberedTypeName != null && rememberedTypeName in names -> rememberedTypeName
            else -> names.firstOrNull()
        }
    }

    // ── Create action ─────────────────────────────────────────────────────────

    override fun doValidate(): ValidationInfo? {
        if (summaryField.text.isBlank()) return ValidationInfo("Summary is required", summaryField)
        if (typeCombo.selectedItem == null) return ValidationInfo("Select a type", typeCombo)
        val parent = parentField.text.trim()
        if (parent.isNotBlank() && !parent.matches(Regex("[A-Za-z]+-[0-9]+")))
            return ValidationInfo(
                "Parent ticket doesn't look like a ticket key (e.g. CW-123)",
                parentField
            )
        return null
    }

    override fun doOKAction() {
        val validation = doValidate()
        if (validation != null) {
            setErrorText(validation.message, validation.component as? JComponent)
            return
        }

        val summary = summaryField.text.trim()
        val description = descriptionArea.text
        val parent = parentField.text.trim().uppercase().takeIf { it.isNotBlank() }
        val typeName = typeCombo.selectedItem as? String
        val subtaskId = if (parent != null) subtaskTypeIdByName[typeName] else null

        setBusy(true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = JiraService.createTicket(
                summary = summary,
                description = description,
                parentKey = parent,
                typeName = if (parent == null) typeName else null,
                subtaskTypeId = subtaskId
            )
            SwingUtilities.invokeLater {
                setBusy(false)
                result.onSuccess {
                    tabDir?.mkdirs()
                    if (parent != null) tabDir?.let {
                        File(
                            it,
                            "new-ticket-parent"
                        ).writeText(parent)
                    }
                    typeName?.let { t -> tabDir?.let { File(it, "new-ticket-type").writeText(t) } }
                    super@NewTicketDialog.doOKAction() // closes the dialog with OK_EXIT_CODE
                }
                result.onFailure { e -> setErrorText(e.message ?: "Failed to create ticket") }
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
        isOKActionEnabled = !busy
        parentField.isEnabled = !busy
        typeCombo.isEnabled = !busy && typeCombo.itemCount > 0
        summaryField.isEnabled = !busy
        descriptionArea.isEnabled = !busy
        if (busy) {
            var frame = 0
            okAction.putValue(Action.NAME, "Creating…")
            spinTimer = Timer(120) {
                okAction.putValue(Action.SMALL_ICON, spinIcons[frame++ % spinIcons.size])
            }.also { it.start() }
        } else {
            okAction.putValue(Action.NAME, "Create")
            okAction.putValue(Action.SMALL_ICON, null)
        }
    }
}
