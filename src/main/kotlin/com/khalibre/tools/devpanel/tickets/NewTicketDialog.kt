package com.khalibre.tools.devpanel.tickets

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.util.ui.JBUI
import com.google.gson.JsonObject
import com.khalibre.tools.devpanel.common.ProjectPaths
import com.khalibre.tools.devpanel.pr.ClipboardImage
import java.awt.*
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class NewTicketDialog(
    private val project: Project,
    private val tabId: String
) : DialogWrapper(project, true) {

    /** Set after a successful create (once the dialog has closed with OK) so the caller can
     *  show a result balloon — e.g. "✓ Created CW-123 (image attach failed: ...)" */
    var resultMessage: String? = null
        private set

    private val cwDir: File? = ProjectPaths.cwDir(project)
    private val tabDir: File? = cwDir?.let { TicketTabsStore.tabStateDir(it, tabId) }

    private val clearParentExtension = ExtendableTextComponent.Extension.create(
        AllIcons.Actions.Close, AllIcons.Actions.CloseHovered, "Clear parent ticket"
    ) { clearParent() }

    private val parentField = ExtendableTextField().apply { toolTipText = "e.g. CW-123" }
    private val typeCombo = ComboBox<String>()
    private val summaryField = JBTextField()

    // ── TipTap rich-text editor replaces the old JEditorPane ──────────────
    private val descriptionEditor = MarkdownDescriptionEditor()

    private var regularTypeInfos: List<JiraMetaService.IssueTypeInfo> = emptyList()
    private var subtaskTypeInfos: List<JiraMetaService.SubtaskTypeInfo> = emptyList()
    private var subtaskTypeIdByName: Map<String, String> = emptyMap()
    private var currentIconUrlByName: Map<String, String> = emptyMap()
    private var metaLoaded = false
    private var rememberedTypeName: String? = null
    private var busy = false

    init {
        title = "New Ticket"
        setOKButtonText("Create")
        typeCombo.renderer = TypeCellRenderer()
        loadRememberedDefaults()
        init()
        updateOkEnabled() // disabled until Summary has text
        loadTypeMeta()

        parentField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onParentChanged()
            override fun removeUpdate(e: DocumentEvent) = onParentChanged()
            override fun changedUpdate(e: DocumentEvent) = onParentChanged()
        })
        summaryField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateOkEnabled()
            override fun removeUpdate(e: DocumentEvent) = updateOkEnabled()
            override fun changedUpdate(e: DocumentEvent) = updateOkEnabled()
        })
    }

    override fun createCenterPanel(): JComponent {
        val topRow = JPanel(GridLayout(1, 2, 12, 0)).apply {
            add(labeledField("Parent ticket", parentField))
            add(labeledField("Type", typeCombo))
        }
        val secondRow = JPanel(GridLayout(1, 1)).apply {
            add(labeledField("Summary", summaryField))
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            preferredSize = Dimension(520, 340) // slightly larger to fit the toolbar + editor
            add(topRow)
            add(Box.createVerticalStrut(10))
            add(secondRow)
            add(Box.createVerticalStrut(10))
            add(labeledField("Description", descriptionEditor))
        }
    }

    private fun labeledField(label: String, field: JComponent): JComponent =
        JPanel(BorderLayout()).apply {
            add(JBLabel(label).apply { border = JBUI.Borders.emptyBottom(4) }, BorderLayout.NORTH)
            add(field, BorderLayout.CENTER)
        }

    override fun getPreferredFocusedComponent(): JComponent {
        return if (parentField.text.isBlank()) {
            parentField
        } else {
            summaryField
        }
    }

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
            val regular = JiraMetaCache.current().types.ifEmpty { JiraMetaService.loadTypes(dir) }
            val subtasks = JiraMetaService.loadSubtaskTypes(dir)
            SwingUtilities.invokeLater {
                regularTypeInfos = regular
                subtaskTypeInfos = subtasks
                subtaskTypeIdByName = subtasks.associate { it.name to it.id }
                metaLoaded = true
                rebuildTypeCombo()
            }
        }
    }

    private fun rebuildTypeCombo() {
        if (!metaLoaded) return
        val hasParent = parentField.text.isNotBlank()
        val names =
            if (hasParent) subtaskTypeInfos.map { it.name } else regularTypeInfos.map { it.name }
        currentIconUrlByName =
            if (hasParent) subtaskTypeInfos.associate { it.name to it.iconUrl }
            else regularTypeInfos.associate { it.name to it.iconUrl }

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

    /** Icon + name renderer, reusing the same cached icons TicketsPanel's type badges use. */
    private inner class TypeCellRenderer : ListCellRenderer<String> {
        private val label = JLabel()
        override fun getListCellRendererComponent(
            list: JList<out String>,
            value: String?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            label.text = value ?: ""
            label.icon = null
            label.iconTextGap = 6
            label.isOpaque = true
            label.border = JBUI.Borders.empty(2, 4)
            if (isSelected) {
                label.background = list.selectionBackground; label.foreground =
                    list.selectionForeground
            } else {
                label.background = list.background; label.foreground = list.foreground
            }
            if (value != null) {
                val cached = JiraIconLoader.cachedIconOrNull(value, 16)
                if (cached != null) {
                    label.icon = cached
                } else {
                    currentIconUrlByName[value]?.takeIf { it.isNotBlank() }?.let { url ->
                        JiraIconLoader.loadTypeIconAsync(
                            project,
                            value,
                            url,
                            16
                        ) { typeCombo.repaint() }
                    }
                }
            }
            return label
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
        val parent = parentField.text.trim().uppercase().takeIf { it.isNotBlank() }
        val typeName = typeCombo.selectedItem as? String
        val subtaskId = if (parent != null) subtaskTypeIdByName[typeName] else null

        setBusy(true)
        ApplicationManager.getApplication().executeOnPooledThread {
            // Get description from the TipTap editor. When it has content, pull out any images
            // embedded via paste or the file-picker button before converting to ADF — they can't
            // be uploaded as real Jira attachments until the ticket (and its numeric id) exists,
            // so the initial create goes out without them and the description gets patched
            // afterwards once they're attached.
            var descriptionAdf: JsonObject? = null
            var pendingImages: List<JiraRichText.EmbeddedImage> = emptyList()

            if (!descriptionEditor.isEmpty()) {
                val html = descriptionEditor.getContentHTML()
                if (html != null && !JiraRichText.isBlankHtml(html)) {
                    val (rewrittenHtml, images) = JiraRichText.extractEmbeddedImages(html)
                    pendingImages = images
                    descriptionAdf = try {
                        JiraRichText.htmlToAdf(rewrittenHtml)
                    } catch (e: Exception) {
                        println("[NewTicketDialog] ADF conversion error: ${e.message}")
                        null
                    }
                }
            } else {
                // Fallback: try clipboard for HTML content (same as before). Doesn't go through
                // the image pipeline above — this is raw external HTML, not our own editor's.
                descriptionAdf = clipboardFallbackDescriptionAdf()
            }

            val creationAdf =
                if (pendingImages.isNotEmpty() && descriptionAdf != null)
                    JiraRichText.stripPendingMedia(descriptionAdf)
                else descriptionAdf

            val createResult = JiraService.createTicket(
                summary = summary,
                descriptionAdf = creationAdf,
                parentKey = parent,
                typeName = if (parent == null) typeName else null,
                subtaskTypeId = subtaskId,
                cwDir = cwDir
            )

            var successMessage: String? = null
            var failureMessage: String? = null

            createResult.onSuccess { issue ->
                successMessage = "✓ Created ${issue.key}"

                // Upload each embedded image as a real attachment, then patch the description
                // in with working media references.
                if (pendingImages.isNotEmpty() && descriptionAdf != null) {
                    val resolved = mutableMapOf<String, String>()
                    var uploadFailures = 0
                    pendingImages.forEach { image ->
                        val ext = image.filename.substringAfterLast('.', "png")
                        val tmp = File.createTempFile("desc-image-", ".$ext")
                        try {
                            tmp.writeBytes(image.bytes)
                            JiraService.uploadAttachment(issue.key, tmp, image.filename, image.mimeType)
                                .onSuccess { attachment -> resolved[image.placeholderId] = attachment.id }
                                .onFailure { uploadFailures++ }
                        } finally {
                            tmp.delete()
                        }
                    }
                    if (resolved.isNotEmpty()) {
                        val finalAdf =
                            JiraRichText.resolveMediaPlaceholders(descriptionAdf, resolved, issue.id)
                        JiraService.updateDescription(issue.key, finalAdf).onFailure {
                            successMessage += " (description image update failed: ${it.message})"
                        }
                    }
                    if (uploadFailures > 0) {
                        successMessage += " ($uploadFailures image${if (uploadFailures > 1) "s" else ""} failed to attach)"
                    }
                }

                // Independent of description: if there's an image on the clipboard, attach it.
                val image = try {
                    ClipboardImage.read()
                } catch (_: Exception) {
                    null
                }
                if (image != null) {
                    val tmp = File.createTempFile("subtask-image-", ".png")
                    try {
                        ImageIO.write(image, "png", tmp)
                        JiraService.uploadAttachment(issue.key, tmp, "screenshot.png").onFailure {
                            successMessage += " (image attach failed: ${it.message})"
                        }
                    } finally {
                        tmp.delete()
                    }
                }
            }
            createResult.onFailure { failureMessage = it.message ?: "Failed to create ticket" }

            SwingUtilities.invokeLater {
                setBusy(false)
                if (failureMessage != null) {
                    setErrorText(failureMessage)
                } else {
                    tabDir?.mkdirs()
                    if (parent != null) tabDir?.let {
                        File(
                            it,
                            "new-ticket-parent"
                        ).writeText(parent)
                    }
                    typeName?.let { t -> tabDir?.let { File(it, "new-ticket-type").writeText(t) } }
                    resultMessage = successMessage
                    super@NewTicketDialog.doOKAction() // closes the dialog with OK_EXIT_CODE
                }
            }
        }
    }

    /**
     * Clipboard fallback when the description editor is empty — reads HTML from clipboard
     * and converts to ADF. This preserves the original behavior of auto-populating from
     * clipboard when the user leaves description blank.
     */
    private fun clipboardFallbackDescriptionAdf(): com.google.gson.JsonObject? {
        return try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val contents = clipboard.getContents(null) ?: return null

            // Try HTML flavor first
            JiraRichText.readHtmlFlavor(contents)?.let { rawHtml ->
                val sanitized = JiraRichText.sanitizeForPaste(rawHtml)
                return JiraRichText.htmlToAdf(sanitized)
            }

            // Fall back to plain text
            if (contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                val text =
                    (contents.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String)?.trim()
                if (!text.isNullOrBlank()) {
                    val html = JiraRichText.plainTextToHtmlFragment(text)
                    return JiraRichText.htmlToAdf(html)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private var spinTimer: Timer? = null
    private val spinIcons = listOf(
        AllIcons.Process.Step_1,
        AllIcons.Process.Step_2,
        AllIcons.Process.Step_3,
        AllIcons.Process.Step_4
    )

    private fun updateOkEnabled() {
        isOKActionEnabled = !busy && summaryField.text.isNotBlank()
    }

    private fun setBusy(busyState: Boolean) {
        busy = busyState
        spinTimer?.stop(); spinTimer = null
        updateOkEnabled()
        parentField.isEnabled = !busy
        typeCombo.isEnabled = !busy && typeCombo.itemCount > 0
        summaryField.isEnabled = !busy
        descriptionEditor.setEditable(!busy)
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
