/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.adobe.clawdea.chat

import com.adobe.clawdea.CLAUDE_DIR
import com.adobe.clawdea.chat.editreview.EditReviewCoordinator
import com.adobe.clawdea.chat.editreview.EditReviewHandler
import com.adobe.clawdea.chat.permission.AskUserQuestionRenderer
import com.adobe.clawdea.chat.permission.ClaudePermissionSettingsWriter
import com.adobe.clawdea.chat.permission.PermissionDispatcher
import com.adobe.clawdea.chat.permission.PermissionDispatcherHolder
import com.adobe.clawdea.chat.permission.PermissionRequestHandler
import com.adobe.clawdea.chat.permission.PermissionRequestRenderer
import com.adobe.clawdea.cli.CliBridge
import com.adobe.clawdea.gateway.ModelEntry
import com.adobe.clawdea.settings.ClawDEASettings
import com.adobe.clawdea.commands.*
import com.adobe.clawdea.commands.handlers.*
import com.adobe.clawdea.skills.ScanStats
import com.adobe.clawdea.skills.SkillRoot
import com.adobe.clawdea.skills.SkillScanner
import com.adobe.clawdea.util.runReadAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class ChatPanel(
    override val bridge: CliBridge,
    override val project: Project,
) : JPanel(BorderLayout()), Disposable, ChatPanelHost, InputHost {

    override val renderer = MessageRenderer(
        autoAcceptEdits = ClawDEASettings.getInstance().state.run { autoAcceptEdits || toolApprovalMode == "allow-all" },
        projectBasePath = project.basePath,
    )
    private val indexQueryHandler = IndexQueryHandler(project)
    private val commandRegistry = CommandRegistry()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(ChatPanel::class.java)

    private val browser: JBCefBrowser = JBCefBrowser()

    override val inputArea = JTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    }

    private val statusLabel = JLabel(" ").apply {
        font = font.deriveFont(11f)
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
    }

    private val modelCombo: JComboBox<ModelEntry> = JComboBox()
    private lateinit var modelComboManager: ModelComboManager

    private val effortCombo: JComboBox<EffortComboManager.EffortEntry> = JComboBox()
    private lateinit var effortComboManager: EffortComboManager

    private val mentionManager = MentionAutocompleteManager(this, project)
    private val slashManager = SlashCommandManager(this, commandRegistry)

    // Mode buttons
    private var currentMode = "Auto"
    private lateinit var modeButtons: Map<String, JToggleButton>

    // JS→Kotlin bridge for stop button in JCEF
    private val abortQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    // Task widget
    private val taskWidget = TaskWidgetController()

    // JS→Kotlin bridge for opening diff editor from chat link
    private val openDiffQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    // JS→Kotlin bridge for Layer 2 Accept/Reject button clicks
    private val editActionQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    // Round-trip probe used by the view-health monitor to detect a frozen CEF renderer.
    private val healthQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    // JS→Kotlin bridge for opening a file in the editor from Read links
    private val openFileQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    // JS→Kotlin bridge for navigating to code references in assistant text
    private val navigateQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    // JS→Kotlin bridge for Allow/Deny button clicks on permission approval cards
    private val permissionDecisionQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    // JS→Kotlin bridge for drift banner action clicks (refresh / dismiss)
    private val driftActionQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    private val htmlTemplate = ChatHtmlTemplate()
    private val browserRenderer = ChatBrowserRenderer(
        browser, htmlTemplate, abortQuery, openDiffQuery, editActionQuery,
        healthQuery, openFileQuery, navigateQuery, permissionDecisionQuery,
        driftActionQuery,
    )

    // Unregister handle for the DriftDetectionService listener; populated in init, called from dispose().
    private lateinit var driftListenerUnregister: () -> Unit

    // Top-of-chat banner for pending drift events (wiki staleness)
    private val driftBanner = DriftBanner(
        updateHtml = { html -> browserRenderer.updateDriftBanner(html) },
        onInsertCommand = { cmd ->
            ApplicationManager.getApplication().invokeLater {
                clearPlaceholder()
                inputArea.text = "$cmd "
                inputArea.caretPosition = inputArea.text.length
                inputArea.requestFocusInWindow()
            }
        },
        onDismissAll = {
            scope.launch(Dispatchers.IO) {
                val service = project.getService(com.adobe.clawdea.knowledge.drift.DriftDetectionService::class.java)
                for (event in service.current()) {
                    service.dismiss(event.signature)
                }
            }
        },
    )

    // Edit review handler: owns the EditReviewCoordinator and diff/action JS bridges
    private val editReviewHandler = EditReviewHandler(project, bridge, EditReviewCoordinator(), browserRenderer, renderer, scope)

    // Permission approval: renderer for cards, dispatcher for the blocking submit/resolve, and
    // the handler that wires PermissionDispatcher onRender → browser + JS bridge → dispatcher.resolve.
    private val permissionRequestRenderer = PermissionRequestRenderer(renderer)
    private val askUserQuestionRenderer = AskUserQuestionRenderer(renderer)
    private val permissionDispatcher: PermissionDispatcher = PermissionDispatcher(
        onRender = { req -> permissionRequestHandler.onRender(req) },
        onAutoAllowed = { req -> permissionRequestHandler.onAutoAllowed(req) },
    )
    private val permissionRequestHandler: PermissionRequestHandler = PermissionRequestHandler(
        dispatcher = permissionDispatcher,
        renderer = permissionRequestRenderer,
        questionRenderer = askUserQuestionRenderer,
        browserRenderer = browserRenderer,
        settingsWriter = project.basePath?.let { ClaudePermissionSettingsWriter(Path.of(it)) },
    )

    // Input placeholder
    private val PLACEHOLDER = "Ask Claude anything...  @file  /cmd+TAB  //skills"
    override var showingPlaceholder = true

    private lateinit var turnController: TurnController
    private val pendingPromptController = PendingPromptController()

    // Skill tracking
    private var cliBridgeHandlesSkills: Boolean = true
    private var registeredSkillAliases: List<String> = emptyList()
    private var discoveredSkills: List<com.adobe.clawdea.skills.SkillInfo> = emptyList()

    // Event stream handler
    private lateinit var eventHandler: EventStreamHandler

    // Session manager (constructed in init after turnController exists)
    private lateinit var sessionManager: SessionManager

    // Context tracking
    private val contextLabel = JLabel("").apply {
        font = font.deriveFont(10f)
        foreground = UIManager.getColor("Label.disabledForeground")
        border = BorderFactory.createEmptyBorder(0, 0, 0, 8)
    }

    init {
        // Turn controller: delegates pause/resume/abort callbacks to ChatPanel UI
        turnController = TurnController(
            onPause = {
                clearQueuedPrompt()
                bridge.abort()
                if (eventHandler.messageBuffer.isNotEmpty()) {
                    browserRenderer.appendHtml(renderer.renderAssistantText(eventHandler.messageBuffer.toString()))
                    eventHandler.messageBuffer.clear()
                }
                browserRenderer.hideThinkingIndicator()
                browserRenderer.hideAllStopButtons()
                browserRenderer.showPausedBanner()
                statusLabel.text = "Paused — Enter to continue, type to steer, ESC to abort"
                inputArea.requestFocusInWindow()
            },
            onResume = { text ->
                scope.launch {
                    if (!bridge.isRunning) {
                        val previousSessionId = bridge.sessionId
                        try {
                            clearQueuedPrompt()
                            withContext(Dispatchers.IO) {
                                bridge.restart(resumeSessionId = previousSessionId, skills = discoveredSkills)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            browserRenderer.hidePausedBanner()
                            browserRenderer.appendHtml(renderer.renderError("Failed to resume Claude CLI: ${e.message}"))
                            statusLabel.text = " "
                            return@launch
                        }
                    }
                    browserRenderer.hidePausedBanner()
                    browserRenderer.appendHtml(renderer.renderUserMessage(text))
                    browserRenderer.showThinkingIndicator()
                    bridge.sendMessage(text)
                    turnController.setStreaming(true)
                    syncStreamingUi()
                    eventHandler.turnHasContent = false
                    eventHandler.streamStartTime = System.currentTimeMillis()
                    statusLabel.text = "Claude is thinking..."
                    inputArea.text = ""
                }
            },
            onAbort = {
                clearQueuedPrompt()
                browserRenderer.hidePausedBanner()
                browserRenderer.appendHtml(renderer.renderError("Response aborted by user"))
                statusLabel.text = " "
            },
        )

        // Title bar with mode buttons
        add(createTitleBar(), BorderLayout.NORTH)

        // JCEF browser for message display
        add(browser.component, BorderLayout.CENTER)

        // Bottom: input + status
        add(createBottomPanel(), BorderLayout.SOUTH)

        // Abort bridge: JS stopTool() → Kotlin abort()
        abortQuery.addHandler {
            ApplicationManager.getApplication().invokeLater { abort() }
            JBCefJSQuery.Response("ok")
        }

        // Event stream handler: processes CLI events from the bridge
        eventHandler = EventStreamHandler(
            bridge = bridge,
            renderer = renderer,
            browserRenderer = browserRenderer,
            editReviewCoordinator = editReviewHandler.coordinator,
            taskWidget = taskWidget,
            turnController = turnController,
            statusLabel = statusLabel,
            scope = scope,
            onFilesystemRefresh = { path ->
                if (path.isEmpty()) {
                    project.getService(FilesystemRefreshCoordinator::class.java).onBashCompleted()
                } else {
                    project.getService(FilesystemRefreshCoordinator::class.java).onEditApplied(path)
                }
            },
            onContextLabelUpdate = { updateContextLabel() },
            onSyncStreamingUi = { syncStreamingUi() },
            onTurnCompleted = { flushQueuedPromptIfReady() },
            onShowErrorNotification = { msg -> showNotification("ClawDEA", msg, NotificationType.ERROR) },
        )

        // Session manager: handles resume, reload, wake recovery, interactive terminal
        sessionManager = SessionManager(
            project = project,
            bridge = bridge,
            renderer = renderer,
            browserRenderer = browserRenderer,
            turnController = turnController,
            getDiscoveredSkills = { discoveredSkills },
            onResetUi = {
                turnController.resetTurnState()
                clearQueuedPrompt()
                syncStreamingUi()
                browserRenderer.hidePausedBanner()
                browserRenderer.hideThinkingIndicator()
                browserRenderer.hideAllStopButtons()
            },
            onRestartAfterTerminal = {
                scope.launch {
                    try {
                        turnController.resetTurnState()
                        clearQueuedPrompt()
                        syncStreamingUi()
                        browserRenderer.hidePausedBanner()
                        browserRenderer.hideThinkingIndicator()
                        browserRenderer.hideAllStopButtons()
                        withContext(Dispatchers.IO) { bridge.restart(skills = discoveredSkills) }
                        appendHtml(renderer.renderInfoMessage("Session restarted to apply changes."))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        appendHtml(renderer.renderError("Failed to restart session: ${e.message}"))
                    }
                }
            },
        )

        // Edit review bridges: openDiff and editAction JS queries
        editReviewHandler.setupOpenDiffHandler(openDiffQuery)
        editReviewHandler.setupEditActionHandler(editActionQuery)

        // Permission approval bridge: Allow/Deny buttons from permission cards.
        permissionRequestHandler.install(permissionDecisionQuery)

        // Expose this panel's dispatcher to the project-level McpServer so the
        // `request_permission` MCP tool can call into it.
        PermissionDispatcherHolder.getInstance(project).set(permissionDispatcher)

        // Open-file bridge: Read tool links open the file in the editor
        openFileQuery.addHandler { filePath ->
            ApplicationManager.getApplication().invokeLater {
                val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
                if (vf != null) {
                    FileEditorManager.getInstance(project).openFile(vf, true)
                }
            }
            JBCefJSQuery.Response("ok")
        }

        // Navigate bridge: code reference links in assistant text
        navigateQuery.addHandler { ref ->
            ApplicationManager.getApplication().executeOnPooledThread {
                navigateToRef(ref)
            }
            JBCefJSQuery.Response("ok")
        }

        // Drift banner action bridge: /refresh-wiki / dismiss clicks
        driftActionQuery.addHandler { action ->
            ApplicationManager.getApplication().invokeLater {
                driftBanner.handleAction(action)
            }
            JBCefJSQuery.Response("ok")
        }

        // Listen for drift detection events: update banner + emit auto-apply notifications.
        val driftService = project.getService(com.adobe.clawdea.knowledge.drift.DriftDetectionService::class.java)
        driftListenerUnregister = driftService.addListener { events, applied ->
            ApplicationManager.getApplication().invokeLater {
                driftBanner.setEvents(events)
                for (line in driftBanner.autoApplyNotificationLines(applied)) {
                    appendHtml(renderer.renderInfoMessage(line))
                }
            }
        }
        // Initial render based on current state (StartupActivity may have already run rescan).
        ApplicationManager.getApplication().invokeLater {
            driftBanner.setEvents(driftService.current())
        }

        // Load chat HTML
        browserRenderer.loadPage()

        // Keyboard handling
        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // Clear placeholder on first real keystroke (covers typing without click-focus)
                if (showingPlaceholder && e.keyChar != KeyEvent.CHAR_UNDEFINED
                    && !e.isActionKey && e.keyCode != KeyEvent.VK_SHIFT
                    && e.keyCode != KeyEvent.VK_CONTROL && e.keyCode != KeyEvent.VK_ALT
                    && e.keyCode != KeyEvent.VK_META && e.keyCode != KeyEvent.VK_ENTER
                    && e.keyCode != KeyEvent.VK_ESCAPE && e.keyCode != KeyEvent.VK_TAB) {
                    inputArea.text = ""
                    inputArea.foreground = UIManager.getColor("TextArea.foreground")
                    showingPlaceholder = false
                }

                // // → open SkillPickerDialog
                if (e.keyCode == KeyEvent.VK_SLASH && !showingPlaceholder) {
                    val text = inputArea.text
                    val caret = inputArea.caretPosition
                    if (caret == 1 && text == "/") {
                        e.consume()
                        inputArea.text = ""
                        slashManager.hideSlashPopup()
                        openSkillPicker()
                        return
                    }
                }

                // Escape: close mention popup, slash popup, or pause/abort
                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    if (mentionManager.isPopupOpen) {
                        e.consume()
                        mentionManager.hidePopup()
                        return
                    }
                    if (slashManager.isPopupOpen) {
                        e.consume()
                        slashManager.hideSlashPopup()
                        return
                    }
                    if (turnController.isStreaming || turnController.isPaused) {
                        e.consume()
                        turnController.handleEscape()
                        return
                    }
                }

                // Up/Down: navigate mention or slash popup
                if ((mentionManager.isPopupOpen || slashManager.isPopupOpen)
                    && (e.keyCode == KeyEvent.VK_UP || e.keyCode == KeyEvent.VK_DOWN)) {
                    e.consume()
                    val delta = if (e.keyCode == KeyEvent.VK_UP) -1 else 1
                    if (mentionManager.isPopupOpen) mentionManager.navigate(delta)
                    else slashManager.navigate(delta)
                    return
                }

                // TAB: accept mention/slash popup, open mention picker if @prefix, or open slash popup
                if (e.keyCode == KeyEvent.VK_TAB && !showingPlaceholder) {
                    if (mentionManager.isPopupOpen) {
                        e.consume()
                        mentionManager.acceptSelection()
                        return
                    }
                    if (slashManager.isPopupOpen) {
                        e.consume()
                        slashManager.acceptSelection()
                        return
                    }
                    val text = inputArea.text
                    val caret = inputArea.caretPosition
                    val atIdx = text.lastIndexOf('@', (caret - 1).coerceAtLeast(0))
                    if (atIdx >= 0 && !text.substring(atIdx + 1, caret).contains(' ')) {
                        e.consume()
                        mentionManager.openPickerDialog()
                        return
                    }
                    e.consume()
                    slashManager.tryOpenFromTab()
                    return
                }

                // Enter: accept mention popup selection
                if (e.keyCode == KeyEvent.VK_ENTER && mentionManager.isPopupOpen) {
                    e.consume()
                    mentionManager.acceptSelection()
                    return
                }

                if (e.keyCode == KeyEvent.VK_ENTER) {
                    if (e.isShiftDown) {
                        e.consume()
                        inputArea.replaceSelection("\n")
                        return
                    }
                    e.consume()
                    if (slashManager.isPopupOpen) {
                        if (slashManager.acceptSelection()) return
                    }
                    if (turnController.isPaused) {
                        val text = inputArea.text
                        turnController.enterInPaused(text, isBlank = showingPlaceholder || text.isBlank())
                        return
                    }
                    sendCurrentMessage()
                }
            }
        })

        // Placeholder behavior
        setupPlaceholder()
        eventHandler.startEventListener()
        inputArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                mentionManager.checkForMention()
                refreshPendingPromptStatus()
            }

            override fun removeUpdate(e: DocumentEvent) {
                mentionManager.checkForMention()
                refreshPendingPromptStatus()
            }

            override fun changedUpdate(e: DocumentEvent) {
                refreshPendingPromptStatus()
            }
        })
        registerBuiltInCommands()
        registerSkillCommands()

        // Drag-and-drop: accept files dropped onto input area as @ references
        inputArea.transferHandler = FileDropHandler(project.basePath) {
            if (showingPlaceholder) {
                inputArea.text = ""
                inputArea.foreground = UIManager.getColor("TextArea.foreground")
                showingPlaceholder = false
            }
        }

        // Live settings reload: restart the CLI bridge when settings are applied.
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(
                com.adobe.clawdea.settings.SettingsChangedListener.TOPIC,
                object : com.adobe.clawdea.settings.SettingsChangedListener {
                    override fun onSettingsChanged() {
                        if (bridge.isRunning && !turnController.isStreaming) {
                            scope.launch {
                                try {
                                    clearQueuedPrompt()
                                    withContext(Dispatchers.IO) {
                                        bridge.restart(skills = discoveredSkills)
                                    }
                                    appendHtml(renderer.renderInfoMessage("Session restarted to apply new settings."))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    appendHtml(renderer.renderError("Failed to restart session: ${e.message}"))
                                }
                            }
                        }
                    }
                },
            )

        // Chat-view freeze recovery: detect JVM suspend gaps and recover.
        scope.launch(Dispatchers.Default) {
            var lastTickAt = System.currentTimeMillis()
            while (isActive) {
                delay(ChatViewHealthMonitor.TICK_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val elapsed = now - lastTickAt
                lastTickAt = now
                if (ChatViewHealthMonitor.isSuspendGap(
                        elapsed,
                        ChatViewHealthMonitor.TICK_INTERVAL_MS,
                        ChatViewHealthMonitor.GAP_THRESHOLD_MS,
                    )
                ) {
                    log.info("view-health: suspend gap of ${elapsed}ms detected, probing")
                    ApplicationManager.getApplication().invokeLater { sessionManager.onWakeDetected() }
                }
            }
        }
    }

    // ── Title bar ──────────────────────────────────────────────────

    private fun createTitleBar(): JPanel {
        val bar = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(4, 8, 4, 8),
            )
        }

        // Left: tool-approval dropdown (quick access to Settings > Tool approval)
        val settings = ClawDEASettings.getInstance().state
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
        }
        val approvalLabels = arrayOf("Confirm all", "Allow safe", "Allow all")
        val approvalKeys = arrayOf("confirm-all", "allow-safe", "allow-all")
        val messageRenderer = renderer
        fun syncRendererAutoAccept() {
            messageRenderer.autoAcceptEdits = settings.autoAcceptEdits || settings.toolApprovalMode == "allow-all"
        }
        val approvalCombo = ComboBox(DefaultComboBoxModel(approvalLabels)).apply {
            font = font.deriveFont(11f)
            selectedIndex = approvalKeys.indexOf(settings.toolApprovalMode).coerceAtLeast(0)
            toolTipText = "Tool approval policy: Confirm all prompts every tool; " +
                "Allow safe uses Claude's Auto Mode classifier; Allow all bypasses all prompts."
            addActionListener {
                val key = approvalKeys[selectedIndex.coerceIn(0, approvalKeys.lastIndex)]
                settings.toolApprovalMode = key
                syncRendererAutoAccept()
            }
        }
        val autoAcceptEditsCheck = JCheckBox("Auto-accept edits").apply {
            font = font.deriveFont(11f)
            isSelected = settings.autoAcceptEdits
            toolTipText = "Apply edits without showing the diff dialog. Every edit still leaves a clickable diff link in the chat so you can review and revert."
            addActionListener {
                settings.autoAcceptEdits = isSelected
                syncRendererAutoAccept()
            }
        }
        leftPanel.add(approvalCombo)
        leftPanel.add(autoAcceptEditsCheck)
        bar.add(leftPanel, BorderLayout.WEST)

        // Right: segmented mode toggle
        val borderColor = UIManager.getColor("Component.borderColor")
            ?: UIManager.getColor("Separator.foreground")
            ?: Color(100, 100, 100)
        val selectedBg = UIManager.getColor("ToggleButton.selectedBackground")
            ?: UIManager.getColor("Button.default.startBackground")
            ?: Color(75, 110, 175)

        val modes = listOf("Auto", "Plan", "Ask")
        val arc = 10
        val segmented = object : JPanel(GridLayout(1, modes.size, 0, 0)) {
            init { isOpaque = false }
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.clip = java.awt.geom.RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc.toFloat(), arc.toFloat())
                super.paintComponent(g2)
                g2.dispose()
            }
            override fun paintChildren(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.clip = java.awt.geom.RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc.toFloat(), arc.toFloat())
                super.paintChildren(g2)
                g2.dispose()
            }
            override fun paintBorder(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = borderColor
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.dispose()
            }
        }

        val normalBg = UIManager.getColor("Panel.background") ?: background
        modeButtons = modes.mapIndexed { index, mode ->
            mode to object : JToggleButton(mode) {
                override fun paintComponent(g: Graphics) {
                    g.color = if (isSelected) selectedBg else normalBg
                    g.fillRect(0, 0, width, height)
                    super.paintComponent(g)
                }
            }.apply {
                font = font.deriveFont(11f)
                isFocusPainted = false
                isBorderPainted = false
                isContentAreaFilled = false
                isOpaque = false
                preferredSize = Dimension(50, 22)
                isSelected = false
                border = if (index < modes.lastIndex) {
                    BorderFactory.createMatteBorder(0, 0, 0, 1, borderColor)
                } else {
                    BorderFactory.createEmptyBorder()
                }
                addActionListener {
                    setMode(mode)
                }
            }
        }.toMap()
        val group = ButtonGroup()
        for ((_, btn) in modeButtons) {
            group.add(btn)
            segmented.add(btn)
        }
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
        }
        rightPanel.add(segmented)
        bar.add(rightPanel, BorderLayout.EAST)
        setMode(currentMode)

        return bar
    }

    private fun setMode(mode: String) {
        currentMode = mode
        for ((m, btn) in modeButtons) {
            btn.isSelected = m == mode
            btn.repaint()
        }
    }

    // ── Bottom panel ───────────────────────────────────────────────

    private fun syncStreamingUi() {
        modelCombo.isEnabled = !turnController.isStreaming
        effortCombo.isEnabled = !turnController.isStreaming
    }

    private fun createBottomPanel(): JPanel {
        val bottomPanel = JPanel(BorderLayout())

        val inputWrapper = JPanel(BorderLayout()).apply {
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground"))
        }
        inputWrapper.add(JScrollPane(inputArea).apply {
            preferredSize = Dimension(0, 80)
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = null
        }, BorderLayout.CENTER)
        bottomPanel.add(inputWrapper, BorderLayout.CENTER)

        modelComboManager = ModelComboManager(
            project = project,
            modelCombo = modelCombo,
            parentDisposable = this,
            isBridgeAvailable = { bridge.isRunning && !turnController.isStreaming },
            restartBridge = {
                clearQueuedPrompt()
                bridge.restart(skills = discoveredSkills)
            },
            appendInfo = { msg -> browserRenderer.appendHtml(renderer.renderInfoMessage(msg)) },
            appendError = { msg -> browserRenderer.appendHtml(renderer.renderError(msg)) },
        )
        effortComboManager = EffortComboManager(
            project = project,
            effortCombo = effortCombo,
            parentDisposable = this,
            isBridgeAvailable = { bridge.isRunning && !turnController.isStreaming },
            restartBridge = {
                clearQueuedPrompt()
                bridge.restart(skills = discoveredSkills)
            },
            appendInfo = { msg -> browserRenderer.appendHtml(renderer.renderInfoMessage(msg)) },
            appendError = { msg -> browserRenderer.appendHtml(renderer.renderError(msg)) },
        )
        val statusRow = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
        }
        val westRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply {
            border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
        }
        westRow.add(modelCombo)
        westRow.add(effortCombo)
        westRow.add(statusLabel)
        statusRow.add(westRow, BorderLayout.WEST)
        statusRow.add(contextLabel, BorderLayout.EAST)
        bottomPanel.add(statusRow, BorderLayout.SOUTH)

        return bottomPanel
    }

    private fun setupPlaceholder() {
        inputArea.foreground = UIManager.getColor("Label.disabledForeground")
        inputArea.text = PLACEHOLDER
        showingPlaceholder = true

        inputArea.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                if (showingPlaceholder) {
                    inputArea.text = ""
                    inputArea.foreground = UIManager.getColor("TextArea.foreground")
                    showingPlaceholder = false
                }
            }

            override fun focusLost(e: FocusEvent) {
                // Defer placeholder restoration so it runs after any in-progress
                // DnD operation completes (avoids invalid caret state during drop).
                SwingUtilities.invokeLater {
                    if (!inputArea.isFocusOwner && inputArea.text.isBlank()) {
                        inputArea.foreground = UIManager.getColor("Label.disabledForeground")
                        inputArea.text = PLACEHOLDER
                        showingPlaceholder = true
                    }
                }
            }
        })
    }

    private fun updateContextLabel() {
        val settings = ClawDEASettings.getInstance().state
        val budget = settings.chatTokenBudget
        val pct = if (budget > 0) ((eventHandler.totalTokensUsed.toLong() * 100) / budget).toInt().coerceAtMost(100) else 0
        contextLabel.text = "context: $pct% used"
    }

    private fun buildCommandContext(): CommandContext {
        return CommandContext(
            appendHtml = { html -> appendHtml(html) },
            showNotification = { msg -> showNotification("ClawDEA", msg, NotificationType.INFORMATION) },
        )
    }

    private fun registerBuiltInCommands() {
        // Local commands
        commandRegistry.register("/stop", LocalHandler(
            CommandInfo("/stop", "Stop current response", CommandCategory.LOCAL),
        ) { _, _ -> abort() })

        commandRegistry.register("/clear", LocalHandler(
            CommandInfo("/clear", "Clear chat history", CommandCategory.LOCAL),
        ) { _, _ ->
            browserRenderer.clearMessages()
            eventHandler.totalTokensUsed = 0
            updateContextLabel()
            appendHtml(renderer.renderInfoMessage("Chat cleared"))
        })

        commandRegistry.register("/refresh-view", LocalHandler(
            CommandInfo("/refresh-view", "Rebuild the chat view from session history", CommandCategory.LOCAL),
        ) { _, _ ->
            sessionManager.reloadAndReplay("manual")
        })

        commandRegistry.register("/mode", LocalHandler(
            CommandInfo("/mode", "Switch mode (auto/plan/ask)", CommandCategory.LOCAL),
        ) { args, _ ->
            val mode = args.replaceFirstChar { it.uppercase() }
            if (mode in listOf("Auto", "Plan", "Ask")) {
                setMode(mode)
                appendHtml(renderer.renderInfoMessage("Switched to $mode mode"))
            } else {
                appendHtml(renderer.renderError("Unknown mode: $args (use auto, plan, or ask)"))
            }
        })

        commandRegistry.register("/cc", LocalHandler(
            CommandInfo("/cc", "Open Claude Code CLI", CommandCategory.LOCAL),
        ) { _, _ -> sessionManager.openInteractiveTerminal(null) })

        commandRegistry.register("/login", LocalHandler(
            CommandInfo("/login", "Sign in with Claude subscription", CommandCategory.LOCAL),
        ) { _, _ ->
            appendHtml(renderer.renderInfoMessage("Signing in..."))
            com.adobe.clawdea.auth.SubscriptionAuth.getInstance().signIn { status ->
                ApplicationManager.getApplication().invokeLater {
                    if (status.isSignedIn()) {
                        val info = status as? com.adobe.clawdea.auth.AuthStatus.SignedIn
                        val detail = listOfNotNull(info?.email, info?.tier).joinToString(", ")
                        appendHtml(renderer.renderInfoMessage("Signed in" + if (detail.isNotBlank()) " ($detail)" else ""))
                    } else {
                        val error = com.adobe.clawdea.auth.SubscriptionAuth.getInstance().lastSignInError()
                        appendHtml(renderer.renderError(error ?: "Sign-in failed"))
                    }
                }
            }
        })

        // Bridge-forward commands
        for (cmd in listOf("/cost", "/compact", "/context")) {
            commandRegistry.register(cmd, BridgeForwardHandler(
                CommandInfo(cmd, "Forward $cmd to Claude Code", CommandCategory.BRIDGE),
            ))
        }

        commandRegistry.register("/init", BridgeForwardHandler(
            CommandInfo("/init", "Initialize CLAUDE.md for this project", CommandCategory.BRIDGE),
        ))

        commandRegistry.register("/note", com.adobe.clawdea.commands.handlers.NoteAppendHandler(project, scope))

        commandRegistry.register("/promote-to-wiki", com.adobe.clawdea.commands.handlers.PromoteToWikiHandler.create(project))

        commandRegistry.register("/learn", com.adobe.clawdea.commands.handlers.BridgeExpandingHandler(
            CommandInfo("/learn", "Propose a wiki page from the current conversation", CommandCategory.BRIDGE),
        ) { args ->
            val topic = args.trim()
            val opening = if (topic.isEmpty()) {
                "Pick the most useful insight from our recent conversation worth filing as a wiki page."
            } else {
                "Draft a wiki page about \"$topic\" based on our recent conversation."
            }
            val targetPath = if (topic.isEmpty()) ".claude/wiki/concepts/<kebab-case-name>.md" else ".claude/wiki/concepts/$topic.md"
            val linkTarget = if (topic.isEmpty()) {
                "[Concept](concepts/<kebab-case-name>.md)"
            } else {
                val title = topic.split('-', ' ')
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { it.replaceFirstChar { char -> char.titlecase() } }
                "[$title](concepts/$topic.md)"
            }
            """
            $opening

            **CRITICAL:** Use the **propose_write** MCP tool (registered as `mcp__clawdea-intellij__propose_write`).
            The built-in `Write` tool is DISABLED in this environment — calls to it are silently rejected and no file is created.
            If a tool result reports "tool not allowed" or you see an "Unavailable" status, you used the wrong tool;
            retry with propose_write.

            Use propose_write to create $targetPath with:
            - A single-line H1 heading naming the concept
            - A 1–2 paragraph summary of the insight
            - Standard Markdown links to related concepts when relevant, e.g. `[Related Concept](related-concept.md)`
            - Code references as standard markdown links to source files, e.g. `[ClassName](../../../src/main/kotlin/path/to/ClassName.kt)` (path relative to the wiki page; concept pages live three levels deep). Do NOT use the `{[ref:...|...]}` chat-only syntax in markdown files.

            Then use propose_edit (NOT the built-in Edit tool) to update .claude/wiki/index.md so it links $linkTarget,
            unless that link is already there.
            """.trimIndent()
        })

        commandRegistry.register("/seed-wiki", com.adobe.clawdea.commands.handlers.BridgeExpandingHandler(
            CommandInfo("/seed-wiki", "Bootstrap initial wiki pages from project state", CommandCategory.BRIDGE),
        ) { _ ->
            """
            Bootstrap an initial wiki for this project at .claude/wiki/.

            **CRITICAL — TOOL CHOICE:** Use the **propose_write** MCP tool (registered as
            `mcp__clawdea-intellij__propose_write`) for every file you create. The built-in `Write` tool is
            DISABLED in this environment — calls to it are silently rejected and no file is created.
            If a tool result reports "tool not allowed" or you see an "Unavailable" status, you used the
            wrong tool; retry with propose_write.

            Steps:
            1. Read CLAUDE.md (if present), README.md, and the top-level build files (pom.xml,
               package.json, build.gradle.kts) to understand the project shape.
            2. Call the get_primer MCP tool to see the auto-generated REPO_STATE
               (current branch, recent commits, hot files), then identify
               5–10 concept areas worth documenting (main subsystems, key APIs, active
               feature work, architectural decisions worth capturing).
            3. Use propose_write (NOT Write) to create:
               - .claude/wiki/index.md — a TOC with a short intro plus standard Markdown links to each concept page, e.g. `[Concept](concepts/concept.md)`.
               - .claude/wiki/concepts/<kebab-case-name>.md — one file per concept. Each page:
                   * A single-line H1 heading naming the concept
                   * A 1-paragraph summary
                   * Key entry-point class refs as standard markdown links to source files, e.g. `[ClassName](../../../src/main/kotlin/path/to/ClassName.kt)` (path relative to the wiki page; concept pages live three levels deep). Do NOT use the `{[ref:...|...]}` chat-only syntax in markdown files.
                   * Gotchas if any
                   Keep each page short — wikis grow organically.

            After I accept the diffs, wiki/index.md will join every chat's primer automatically.
            """.trimIndent()
        })

        commandRegistry.register("/seed-workspace", com.adobe.clawdea.commands.handlers.BridgeExpandingHandler(
            CommandInfo("/seed-workspace", "Discover related project repos and propose a workspace manifest", CommandCategory.BRIDGE),
        ) { _ ->
            val basePath = project.basePath
                ?: return@BridgeExpandingHandler "(no project basePath — cannot seed a workspace from this chat)"
            val start = java.nio.file.Paths.get(basePath)

            val roots = com.adobe.clawdea.knowledge.workspace.seed.WorkspaceRootDetector.detect(start)
            if (roots.isEmpty()) {
                return@BridgeExpandingHandler "This project doesn't appear to be in a workspace " +
                    "(no qualifying parent had ≥${com.adobe.clawdea.knowledge.workspace.seed.WorkspaceRootDetector.MIN_SIBLINGS} sibling git repos). " +
                    "Skipping `/seed-workspace`."
            }

            val openProjects = com.intellij.openapi.project.ProjectManager.getInstance().openProjects
                .mapNotNull { it.basePath?.let { p -> java.nio.file.Paths.get(p) } }
            val recentProjects = (com.intellij.ide.RecentProjectsManager.getInstance() as? com.intellij.ide.RecentProjectsManagerBase)
                ?.getRecentPaths()
                .orEmpty()
                .take(20).map { java.nio.file.Paths.get(it) }

            val workspaceRoot = roots.first()  // deepest; Claude surfaces the full list to the user
            val candidatePaths = com.adobe.clawdea.knowledge.workspace.seed.CandidateScanner.scan(
                workspaceRoot, openProjects, recentProjects,
            )

            val fingerprints = candidatePaths.map { p ->
                com.adobe.clawdea.knowledge.workspace.seed.CandidateFingerprinter.fingerprint(p, gitLog = emptyList())
            }
            val suggested = com.adobe.clawdea.knowledge.workspace.seed.CandidateClusterer.cluster(fingerprints)

            val fingerprintsByKey = fingerprints.associateBy { it.key }
            val suggestedDeps = com.adobe.clawdea.knowledge.workspace.seed.CandidateClusterer
                .discoverCrossGroupDeps(suggested, fingerprintsByKey)

            val existing = com.adobe.clawdea.knowledge.workspace.WorkspaceDiscovery.discover(start)
                ?.let { runCatching { java.nio.file.Files.readString(it) }.getOrNull() }

            com.adobe.clawdea.knowledge.workspace.seed.SeedPromptBuilder.build(roots, suggested, suggestedDeps, existing)
        })

        commandRegistry.register("/refresh-wiki", LocalHandler(
            CommandInfo("/refresh-wiki", "Review and fix detected wiki drift events", CommandCategory.BRIDGE),
        ) { rawArgs, _ ->
            val args = RefreshWikiArgs.parse(rawArgs)
            if (args.statusOnly) {
                appendHtml(renderer.renderInfoMessage(refreshWikiStatus()))
                return@LocalHandler
            }

            appendHtml(renderer.renderInfoMessage("Refreshing wiki drift..."))
            scope.launch {
                val result = withContext(Dispatchers.IO) { refreshWiki(args) }
                if (result.sendToBridge) {
                    dispatchSendToBridge(result.text, renderInChat = false)
                } else {
                    appendHtml(renderer.renderInfoMessage(result.text))
                }
            }
        })

        // Index query commands
        for (cmd in listOf("/callers", "/implementations", "/usages", "/supertypes")) {
            commandRegistry.register(cmd, IndexQueryCommandHandler(
                CommandInfo(cmd, "${cmd.removePrefix("/")} of symbol at cursor", CommandCategory.INDEX),
                indexQueryHandler,
            ) { FileEditorManager.getInstance(project).selectedTextEditor })
        }

        // Dialog commands
        commandRegistry.register("/resume", LocalHandler(
            CommandInfo("/resume", "Resume a previous session", CommandCategory.DIALOG),
        ) { _, _ -> sessionManager.openResumeDialog("/resume") })

        commandRegistry.register("/skills", LocalHandler(
            CommandInfo("/skills", "Browse and invoke skills", CommandCategory.DIALOG),
        ) { args, _ ->
            if (args == "refresh") {
                val stats = registerSkillCommands()
                appendHtml(renderer.renderInfoMessage(formatRefreshMessage(stats)))
            } else {
                openSkillPicker()
            }
        })
        commandRegistry.register("/wiki-audit", com.adobe.clawdea.commands.handlers.WikiAuditCommandHandler(project))
    }

    private data class RefreshWikiCommandResult(
        val text: String,
        val sendToBridge: Boolean,
    )

    private fun refreshWiki(args: RefreshWikiArgs): RefreshWikiCommandResult {
        if (args.applyLowRisk && !ClawDEASettings.getInstance().state.autoUpdateWiki) {
            return RefreshWikiCommandResult(
                "Applying low-risk wiki drift fixes requires enabling Auto-update wiki on drift.",
                sendToBridge = false,
            )
        }

        val service = project.getService(com.adobe.clawdea.knowledge.drift.DriftDetectionService::class.java)
        val (events, _) = if (args.forceDream) {
            service.runDreamScanNow()
        } else {
            service.rescan()
        }

        if (args.applyLowRisk) {
            return RefreshWikiCommandResult(formatAppliedWikiDrift(service.lastAppliedEvents()), sendToBridge = false)
        }

        if (events.isEmpty()) {
            return RefreshWikiCommandResult("(no drift events detected)", sendToBridge = false)
        }

        return RefreshWikiCommandResult(buildRefreshWikiPrompt(events), sendToBridge = true)
    }

    private fun refreshWikiStatus(): String {
        val basePath = project.basePath ?: return "Dream wiki status: project path unavailable."
        val state = com.adobe.clawdea.knowledge.drift.DriftStateStore.read(java.nio.file.Paths.get(basePath).resolve(".claude"))
        fun valueOrNever(value: String): String = value.ifBlank { "never" }
        return "Dream wiki status: last run ${valueOrNever(state.dreamLastRunAt)}; " +
            "last successful scan ${valueOrNever(state.dreamLastSuccessfulScanAt)}; " +
            "last status ${state.dreamLastStatus.ifBlank { "none" }}; " +
            "filtered candidates ${state.dreamFilteredCandidateCount}."
    }

    private fun formatAppliedWikiDrift(events: List<com.adobe.clawdea.knowledge.drift.DriftEvent>): String {
        if (events.isEmpty()) return "No low-risk wiki drift fixes were applied."
        val summary = events.joinToString(", ") { event ->
            when (event) {
                is com.adobe.clawdea.knowledge.drift.DriftEvent.CodeRename -> "CodeRename ${event.wikiPage.fileName}"
                is com.adobe.clawdea.knowledge.drift.DriftEvent.ManifestStale -> "ManifestStale ${event.repoKey}"
                is com.adobe.clawdea.knowledge.drift.DriftEvent.DreamIndexCleanup -> "DreamIndexCleanup ${event.targetFile.fileName}"
                is com.adobe.clawdea.knowledge.drift.DriftEvent.DreamLinkNormalization -> "DreamLinkNormalization ${event.targetFile.fileName}"
                is com.adobe.clawdea.knowledge.drift.DriftEvent.DreamSourceReferenceFix -> "DreamSourceReferenceFix ${event.targetFile.fileName}"
                is com.adobe.clawdea.knowledge.drift.DriftEvent.DreamDuplicateConcept -> "DreamDuplicateConcept ${event.targetFile.fileName}"
                is com.adobe.clawdea.knowledge.drift.DriftEvent.DreamStaleConcept -> "DreamStaleConcept ${event.targetFile.fileName}"
                is com.adobe.clawdea.knowledge.drift.DriftEvent.DreamMissingConcept -> "DreamMissingConcept ${event.targetFile.fileName}"
            }
        }
        return "Applied ${events.size} low-risk wiki drift fix(es): $summary."
    }

    private fun buildRefreshWikiPrompt(events: List<com.adobe.clawdea.knowledge.drift.DriftEvent>): String {
        val sb = StringBuilder()
        sb.appendLine("The following drift events were detected. Review each and apply fixes via `propose_edit` or `propose_write`:")
        sb.appendLine()
        for (event in events) {
            when (event) {
                is com.adobe.clawdea.knowledge.drift.DriftEvent.CodeRename -> {
                    sb.appendLine("- **CodeRename** in `${event.wikiPage.fileName}`")
                    sb.appendLine("  - broken link: `${event.brokenLink}`")
                    if (event.suggestedReplacement != null) {
                        sb.appendLine("  - suggested replacement: `${event.suggestedReplacement}`")
                        sb.appendLine("  - action: confirm the replacement is the right target, then `propose_edit` `${event.wikiPage}` to update the link.")
                    } else {
                        sb.appendLine("  - no unique basename match was found; either search for the moved file via `find_files` and `propose_edit` the wiki page, or remove the broken link.")
                    }
                }
                is com.adobe.clawdea.knowledge.drift.DriftEvent.ManifestStale -> {
                    sb.appendLine("- **ManifestStale** in `${event.manifestPath.fileName}` (group `${event.groupName}`)")
                    sb.appendLine("  - missing repo key: `${event.repoKey}` (line ${event.lineHint})")
                    sb.appendLine("  - action: check whether the repo moved (update path) or was deleted (`propose_edit` the manifest to comment out or remove the bullet).")
                }
                is com.adobe.clawdea.knowledge.drift.DriftEvent.DreamIndexCleanup -> {
                    appendDreamEvent(sb, "DreamIndexCleanup", event.targetFile, event.title, "use `propose_edit` to clean up the wiki index: ${event.patchPlan}")
                }
                is com.adobe.clawdea.knowledge.drift.DriftEvent.DreamLinkNormalization -> {
                    appendDreamEvent(sb, "DreamLinkNormalization", event.targetFile, event.title, "use `propose_edit` to normalize the link: ${event.patchPlan}")
                }
                is com.adobe.clawdea.knowledge.drift.DriftEvent.DreamSourceReferenceFix -> {
                    appendDreamEvent(sb, "DreamSourceReferenceFix", event.targetFile, event.title, "use `propose_edit` to fix stale source references: ${event.patchPlan}")
                }
                is com.adobe.clawdea.knowledge.drift.DriftEvent.DreamDuplicateConcept -> {
                    appendDreamEvent(sb, "DreamDuplicateConcept", event.targetFile, event.title, "review the overlap, then use `propose_edit` to merge or redirect content if appropriate: ${event.patchPlan}")
                }
                is com.adobe.clawdea.knowledge.drift.DriftEvent.DreamStaleConcept -> {
                    appendDreamEvent(sb, "DreamStaleConcept", event.targetFile, event.title, "verify the concept is stale, then use `propose_edit` to update or remove it: ${event.patchPlan}")
                }
                is com.adobe.clawdea.knowledge.drift.DriftEvent.DreamMissingConcept -> {
                    appendDreamEvent(sb, "DreamMissingConcept", event.targetFile, event.title, "use `propose_write` to draft the missing concept page if it is still useful: ${event.patchPlan}")
                }
            }
        }
        sb.appendLine()
        sb.appendLine("After fixing each event, the user accepts/rejects via the diff dialog as usual.")
        return sb.toString()
    }

    private fun appendDreamEvent(
        sb: StringBuilder,
        eventName: String,
        targetFile: java.nio.file.Path,
        title: String,
        action: String,
    ) {
        sb.appendLine("- **$eventName** in `${targetFile.fileName}`")
        sb.appendLine("  - title: $title")
        sb.appendLine("  - action: $action")
    }

    fun suggestInitIfMissingClaudeMd() = sessionManager.suggestInitIfMissingClaudeMd()

    private fun registerSkillCommands(): ScanStats {
        // Unregister stale skill aliases from a previous scan
        for (alias in registeredSkillAliases) {
            commandRegistry.unregister(alias)
        }

        val scanner = SkillScanner(buildSkillRoots())
        val stats = scanner.scanWithStats()
        discoveredSkills = stats.skills

        val newAliases = mutableListOf<String>()
        for (skill in discoveredSkills) {
            val handler = SkillHandler(
                skillInfo = skill,
                sendToBridge = { text -> sendViaBridge(text) },
                probeResult = { cliBridgeHandlesSkills },
            )
            for (alias in skill.aliases) {
                commandRegistry.register(alias, handler)
                newAliases.add(alias)
            }
        }
        registeredSkillAliases = newAliases
        return stats
    }

    private fun buildSkillRoots(): List<SkillRoot> {
        val home = System.getProperty("user.home")
        val homePath = java.nio.file.Paths.get(home)
        val pluginsDir = homePath.resolve(CLAUDE_DIR).resolve("plugins")
        val roots = mutableListOf<SkillRoot>(
            SkillRoot.PluginCache(pluginsDir.resolve("cache")),
            SkillRoot.PluginCache(pluginsDir.resolve("marketplaces")),
            SkillRoot.Flat(homePath.resolve(CLAUDE_DIR).resolve("skills"), pluginName = "user"),
        )
        project.basePath?.let { base ->
            roots += SkillRoot.Flat(java.nio.file.Paths.get(base, CLAUDE_DIR, "skills"), pluginName = "project")
        }
        return roots
    }

    private fun formatRefreshMessage(stats: ScanStats): String {
        val parts = mutableListOf("Skills refreshed: ${stats.skills.size} found")
        parts += "${stats.rootsScanned} root(s) scanned"
        if (stats.rootsMissing > 0) parts += "${stats.rootsMissing} missing"
        if (stats.rejectedCount > 0) parts += "${stats.rejectedCount} rejected"
        return parts.joinToString(", ") + "."
    }

    private fun sendViaBridge(text: String) {
        if (!bridge.isRunning) {
            try {
                bridge.start(skills = discoveredSkills)
            } catch (e: Exception) {
                appendHtml(renderer.renderError("Failed to start Claude CLI: ${e.message}"))
                return
            }
        }
        appendHtml(renderer.renderUserMessage(text))
        browserRenderer.showThinkingIndicator()
        bridge.sendMessage(text)
        turnController.onUserSend()
        syncStreamingUi()
        eventHandler.turnHasContent = false
        eventHandler.streamStartTime = System.currentTimeMillis()
        statusLabel.text = "Claude is thinking..."
    }

    private fun openSkillPicker() {
        if (discoveredSkills.isEmpty()) {
            appendHtml(renderer.renderInfoMessage("No skills found. Check that Claude Code plugins are installed."))
            return
        }

        val dialog = SkillPickerDialog(project, discoveredSkills)
        if (dialog.showAndGet()) {
            val selected = dialog.selectedSkill
            if (selected != null) {
                val commandName = selected.aliases.first()
                inputArea.text = "$commandName "
                inputArea.caretPosition = inputArea.text.length
                inputArea.requestFocusInWindow()
                if (showingPlaceholder) {
                    inputArea.foreground = UIManager.getColor("TextArea.foreground")
                    showingPlaceholder = false
                }
            }
        }
    }

    // ── Messaging ──────────────────────────────────────────────────

    private fun sendCurrentMessage() {
        if (showingPlaceholder) return
        val text = inputArea.text.trim()
        if (text.isEmpty()) return

        // Reset stale streaming state if CLI died
        if (turnController.isStreaming && !bridge.isRunning) {
            turnController.setStreaming(false)
            syncStreamingUi()
        }

        if (turnController.isStreaming) {
            if (!text.startsWith("/")) {
                queueCurrentComposerText()
                return
            }

            val match = commandRegistry.resolve(text)
            val handler = match?.handler
            if (handler is BridgeForwardHandler ||
                handler is com.adobe.clawdea.commands.handlers.BridgeExpandingHandler ||
                handler is SkillHandler
            ) {
                queueCurrentComposerText()
                return
            }
        }

        inputArea.text = ""
        sendTextThroughNormalRouting(text)
    }

    // Command routing: handle /commands.
    // After this block, `text` holds whatever should be forwarded to the CLI
    // as a user message. For most local handlers we simply `return` and never
    // reach the bridge. BridgeForwardHandler keeps `text` verbatim (works for
    // CLI-native commands like /init, /cost). BridgeExpandingHandler swaps
    // `text` for the expansion template — the CLI does not know the original
    // slash command, so we send the expansion as a normal user message.
    private fun sendTextThroughNormalRouting(text: String) {
        if (text.startsWith("/")) {
            val match = commandRegistry.resolve(text)
            if (match != null) {
                val handler = match.handler
                match.handler.execute(match.args, buildCommandContext())
                when (handler) {
                    is BridgeForwardHandler -> { /* fall through; send `text` verbatim */ }
                    is com.adobe.clawdea.commands.handlers.BridgeExpandingHandler -> {
                        // Expansion may walk the filesystem (e.g. /seed-workspace
                        // pre-flight scans poms/sources). Run off-EDT, then resume
                        // the send on the EDT. The expanded prompt can be long and
                        // verbose (suggested-deps evidence, format reminders, etc.)
                        // — Claude needs it but the user doesn't, so we skip the
                        // user-message render. The placeholder from execute()
                        // ("Expanding /seed-workspace…") is the visible chat marker.
                        scope.launch {
                            val expanded = withContext(Dispatchers.IO) { handler.expand(match.args) }
                            dispatchSendToBridge(expanded, renderInChat = false)
                        }
                        return
                    }
                    else -> return
                }
            } else {
                // Unknown command: fall back to interactive terminal
                sessionManager.openInteractiveTerminal(text)
                return
            }
        }

        dispatchSendToBridge(text)
    }

    private fun queueCurrentComposerText(): Boolean {
        if (showingPlaceholder) return false
        val queued = pendingPromptController.queue(inputArea.text)
        refreshPendingPromptStatus()
        return queued
    }

    private fun clearQueuedPrompt() {
        pendingPromptController.clear()
        refreshPendingPromptStatus()
    }

    private fun refreshPendingPromptStatus() {
        if (pendingPromptController.isQueued && inputArea.text.isBlank()) {
            pendingPromptController.clear()
        }
        if (pendingPromptController.isQueued) {
            statusLabel.text = pendingPromptController.statusText(inputArea.text)
        } else if (turnController.isStreaming) {
            statusLabel.text = "Claude is thinking..."
        } else if (turnController.isPaused) {
            statusLabel.text = "Paused - Enter to continue, type to steer, ESC to abort"
        } else {
            statusLabel.text = " "
        }
    }

    private fun flushQueuedPromptIfReady() {
        val text = pendingPromptController.consume(inputArea.text) ?: run {
            refreshPendingPromptStatus()
            return
        }
        inputArea.text = ""
        sendTextThroughNormalRouting(text)
    }

    /**
     * EDT-only: forward [text] to the CLI bridge and update UI state.
     *
     * When [renderInChat] is true (default), the text is also rendered as a user
     * message in the chat. BridgeExpandingHandler dispatches set this to false
     * so the long expansion prompt stays out of chat scrollback — the placeholder
     * its `execute()` already emitted ("Expanding /seed-workspace…") is the
     * visible chat marker for that turn.
     */
    private fun dispatchSendToBridge(text: String, renderInChat: Boolean = true) {
        // Ensure CLI is running (first start uses CLI's default model)
        if (!bridge.isRunning) {
            try {
                bridge.start(skills = discoveredSkills)
            } catch (e: Exception) {
                val msg = "Failed to start Claude CLI: ${e.message}"
                appendHtml(renderer.renderError(msg))
                showNotification("ClawDEA", msg, NotificationType.ERROR)
                return
            }
        }

        if (renderInChat) {
            appendHtml(renderer.renderUserMessage(text))
        }
        browserRenderer.showThinkingIndicator()
        bridge.sendMessage(text)
        turnController.onUserSend()
        syncStreamingUi()
        eventHandler.turnHasContent = false
        eventHandler.streamStartTime = System.currentTimeMillis()
        statusLabel.text = "Claude is thinking..."

        eventHandler.totalTokensUsed += ContextBudgetCalculator.estimateTokens(text)
        updateContextLabel()
    }

    fun requestAutoResume(sessionId: String) = sessionManager.requestAutoResume(sessionId)

    fun reloadAndReplay(reason: String) = sessionManager.reloadAndReplay(reason)

    /** Single entry point for the ESC keybinding / Pause-or-Abort action. */
    fun handleEscape() {
        turnController.handleEscape()
    }

    fun abort() {
        if (!turnController.isStreaming) return
        bridge.abort()
        turnController.resetTurnState()
        clearQueuedPrompt()
        syncStreamingUi()
        browserRenderer.hidePausedBanner()
        browserRenderer.hideThinkingIndicator()
        browserRenderer.hideAllStopButtons()
        if (eventHandler.messageBuffer.isNotEmpty()) {
            appendHtml(renderer.renderAssistantText(eventHandler.messageBuffer.toString()))
            eventHandler.messageBuffer.clear()
        }
        appendHtml(renderer.renderError("Response aborted by user"))
        statusLabel.text = " "
    }

    private fun navPriority(filePath: String): Int {
        val rel = filePath.removePrefix(project.basePath ?: "")
        return when {
            rel.startsWith("/src/") -> 0
            rel.startsWith("/.claude/worktrees/") || rel.startsWith("/bin/") -> 2
            else -> 1
        }
    }

    private fun navigateToRef(ref: String) {
        val basePath = project.basePath ?: ""

        val parsed = RefParser.parse(ref) ?: return
        val path = parsed.path
        val explicitLine = parsed.startLine
        val line = explicitLine ?: 0
        val col = parsed.column

        // Try file path resolution
        val vf = when {
            path.startsWith("/") -> LocalFileSystem.getInstance().findFileByPath(path)
            path.contains("/") -> LocalFileSystem.getInstance().findFileByPath("$basePath/$path")
            path.contains(".") && !path.contains("(") -> {
                val filename = path.substringAfterLast("/")
                runReadAction {
                    FilenameIndex.getVirtualFilesByName(filename, GlobalSearchScope.projectScope(project))
                        .sortedBy { navPriority(it.path) }
                        .firstOrNull()
                }
            }
            else -> null
        }
        if (vf != null) {
            ApplicationManager.getApplication().invokeLater {
                OpenFileDescriptor(project, vf, line, col).navigate(true)
                if (parsed.isRange) selectLineRange(vf, parsed.startLine!!, parsed.endLine!!)
            }
            return
        }

        // Try PSI resolution (FQCN, Class.method, or bare class name)
        var resolved = false
        if (com.intellij.openapi.project.DumbService.getInstance(project).isDumb) {
            // Indexes not ready — fall through to Search Everywhere
        } else runReadAction {
            val cache = PsiShortNamesCache.getInstance(project)
            val scope = GlobalSearchScope.projectScope(project)

            if (path.contains(".")) {
                val segments = path.removeSuffix("()").split(".")
                val classIdx = segments.indexOfLast { it.firstOrNull()?.isUpperCase() == true }
                if (classIdx >= 0) {
                    val className = segments[classIdx]
                    val fqn = segments.subList(0, classIdx + 1).joinToString(".")
                    val methodName = if (classIdx + 1 < segments.size) segments[classIdx + 1] else null
                    val classes = cache.getClassesByName(className, scope)
                        .sortedBy { navPriority(it.containingFile?.virtualFile?.path ?: "") }
                    val targetClass = classes.firstOrNull { it.qualifiedName == fqn }
                        ?: classes.firstOrNull()
                    if (targetClass != null) {
                        val target = methodName?.let { m ->
                            targetClass.findMethodsByName(m, false).firstOrNull()
                        } ?: targetClass
                        val file = target.containingFile?.virtualFile
                        if (file != null) {
                            resolved = true
                            ApplicationManager.getApplication().invokeLater {
                                val descriptor = if (explicitLine != null) {
                                    OpenFileDescriptor(project, file, explicitLine, col)
                                } else {
                                    OpenFileDescriptor(project, file, target.textOffset)
                                }
                                descriptor.navigate(true)
                                if (parsed.isRange) selectLineRange(file, parsed.startLine!!, parsed.endLine!!)
                            }
                            return@runReadAction
                        }
                    }
                }
            }

            val classes = cache.getClassesByName(path, scope)
            val cls = classes
                .sortedBy { navPriority(it.containingFile?.virtualFile?.path ?: "") }
                .firstOrNull() ?: return@runReadAction
            val file = cls.containingFile?.virtualFile ?: return@runReadAction
            resolved = true
            ApplicationManager.getApplication().invokeLater {
                val descriptor = if (explicitLine != null) {
                    OpenFileDescriptor(project, file, explicitLine, col)
                } else {
                    OpenFileDescriptor(project, file, cls.textOffset)
                }
                descriptor.navigate(true)
                if (parsed.isRange) selectLineRange(file, parsed.startLine!!, parsed.endLine!!)
            }
        }

        if (!resolved) {
            val searchText = path.substringAfterLast(".").removeSuffix("()")
                .ifBlank { path }
            ApplicationManager.getApplication().invokeLater {
                val action = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                    .getAction("SearchEverywhere") ?: return@invokeLater
                val dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                        .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                        .build()
                val event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
                    dataContext,
                    action.templatePresentation.clone(),
                    com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN,
                    com.intellij.openapi.actionSystem.ActionUiKind.NONE,
                    null,
                )
                com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
                    .getInstance(project)
                    .show(com.intellij.ide.actions.searcheverywhere.SearchEverywhereManagerImpl.ALL_CONTRIBUTORS_GROUP_ID, searchText, event)
            }
        }
    }

    /**
     * Select lines `[startLine..endLine]` (0-based, inclusive) in whichever
     * editor was just opened for `file`. The range is clamped to the
     * document; the caret is parked at the start so the selection unfolds
     * downward in the IDE.
     */
    private fun selectLineRange(file: com.intellij.openapi.vfs.VirtualFile, startLine: Int, endLine: Int) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val docFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document)
        if (docFile != file) return
        val doc = editor.document
        if (doc.lineCount == 0) return
        val s = startLine.coerceIn(0, doc.lineCount - 1)
        val e = endLine.coerceIn(s, doc.lineCount - 1)
        val startOffset = doc.getLineStartOffset(s)
        val endOffset = doc.getLineEndOffset(e)
        editor.caretModel.moveToOffset(startOffset)
        editor.selectionModel.setSelection(startOffset, endOffset)
    }

    private fun showNotification(title: String, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("ClawDEA")
            .createNotification(title, message, type)
            .notify(project)
    }

    // ── JCEF rendering (delegated to ChatBrowserRenderer) ─────────

    override fun appendHtml(html: String) = browserRenderer.appendHtml(html)

    override fun appendError(message: String) = browserRenderer.appendHtml(renderer.renderError(message))

    override fun clearPlaceholder() {
        if (showingPlaceholder) {
            inputArea.text = ""
            inputArea.foreground = UIManager.getColor("TextArea.foreground")
            showingPlaceholder = false
        }
    }

    override fun dispose() {
        scope.cancel()
        PermissionDispatcherHolder.getInstance(project).clear(permissionDispatcher)
        if (::driftListenerUnregister.isInitialized) {
            try { driftListenerUnregister() } catch (_: Throwable) {}
        }
        abortQuery.dispose()
        openDiffQuery.dispose()
        editActionQuery.dispose()
        healthQuery.dispose()
        openFileQuery.dispose()
        navigateQuery.dispose()
        permissionDecisionQuery.dispose()
        driftActionQuery.dispose()
        browser.dispose()
    }

}
