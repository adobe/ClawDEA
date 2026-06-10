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
import com.adobe.clawdea.chat.permission.HandlerQuestionService
import com.adobe.clawdea.chat.permission.PermissionDispatcher
import com.adobe.clawdea.chat.permission.PermissionRouter
import com.adobe.clawdea.chat.permission.PermissionRouterRegistry
import com.adobe.clawdea.chat.permission.PermissionRequestHandler
import com.adobe.clawdea.chat.permission.PermissionRequestRenderer
import com.adobe.clawdea.cli.CliBridge
import com.adobe.clawdea.gateway.ModelEntry
import com.adobe.clawdea.language.LanguageSupportRegistry
import com.adobe.clawdea.mcp.McpServer
import com.adobe.clawdea.settings.ClawDEASettings
import com.adobe.clawdea.settings.ToolApprovalModeUi
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
        autoAcceptEdits = ClawDEASettings.getInstance().state.run {
            autoAcceptEdits || ToolApprovalModeUi.isAllowAll(toolApprovalMode)
        },
        projectBasePath = project.basePath,
        wikiDirResolver = {
            try {
                com.adobe.clawdea.knowledge.wiki.WikiLocator.getInstance(project).wikiDir()
            } catch (_: Throwable) {
                null
            }
        },
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
    // JS→Kotlin bridge for the thinking pause/stop control
    private val turnControlQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    // Task widget
    private val taskWidget = TaskWidgetController()
    private val subAgentController = SubAgentController()
    private val goalController = GoalController()

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
    // JS→Kotlin bridge for clickable slash-command links inside chat messages.
    // Used by SessionManager's /seed-wiki suggestion and could power any future
    // "click here to run /foo" affordance.
    private val runSlashCommandQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    // JS→Kotlin bridge for the wiki-git-state banner (fix / dismiss).
    private val wikiGitStateActionQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    private val htmlTemplate = ChatHtmlTemplate()
    private val browserRenderer = ChatBrowserRenderer(
        browser, htmlTemplate, abortQuery, turnControlQuery, openDiffQuery,
        editActionQuery, healthQuery, openFileQuery, navigateQuery,
        permissionDecisionQuery, driftActionQuery, runSlashCommandQuery,
        wikiGitStateActionQuery,
    )

    // Unregister handle for the DriftDetectionService listener; populated in init, called from dispose().
    private lateinit var driftListenerUnregister: () -> Unit

    // Top-of-chat banner for wiki state files with unexpected git tracking status.
    // Stacked above [driftBanner]. Hidden whenever [WikiGitStateChecker.check] returns empty.
    @Volatile
    private var wikiGitStateBannerDismissed: Boolean = false
    private val wikiGitStateBanner = WikiGitStateBanner(
        updateHtml = { html -> browserRenderer.updateWikiGitStateBanner(html) },
        onFix = {
            log.info("WikiGitStateBanner: fix click")
            scope.launch(Dispatchers.IO) {
                val checker = com.adobe.clawdea.knowledge.wiki.WikiGitStateChecker.getInstance(project)
                val issues = checker.check()
                log.info("WikiGitStateBanner.fix: ${issues.size} issue(s) to address")
                for (issue in issues) {
                    runCatching { checker.fix(issue) }.onFailure {
                        log.warn("WikiGitStateBanner.fix: exception fixing $issue: ${it.message}", it)
                    }
                }
                refreshWikiGitStateBanner()
            }
        },
        onDismiss = {
            wikiGitStateBannerDismissed = true
            // Clear the DOM directly; the banner instance itself is what called us,
            // so we can't reference it here without a forward-decl loop.
            browserRenderer.updateWikiGitStateBanner(
                """<div id="wiki-git-state-banner" style="display:none;"></div>""",
            )
        },
    )

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
    )
    private val permissionRequestHandler: PermissionRequestHandler = PermissionRequestHandler(
        dispatcher = permissionDispatcher,
        renderer = permissionRequestRenderer,
        questionRenderer = askUserQuestionRenderer,
        browserRenderer = browserRenderer,
        settingsWriter = project.basePath?.let { ClaudePermissionSettingsWriter(Path.of(it)) },
        // AskUserQuestion answers that arrive after the dispatcher's
        // 45 s submit-timeout cannot reach Claude through the original
        // request_permission round-trip (CC has already finalised it as
        // denied per #50289). Re-inject them as a synthetic user message
        // on the next turn so the conversation continues with the
        // selection rather than Claude re-asking after a "continue".
        // Hidden from chat scrollback (renderInChat=false) since the
        // resolved question card already shows the user's answers.
        onLateAnswer = { msg -> dispatchSendToBridge(msg, renderInChat = false) },
        handlerQuestions = HandlerQuestionService.getInstance(project),
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

    // Counts consecutive turn-start stall recoveries since the last successful turn.
    // First stall → restart with --resume (handles transient slow-first-byte / network blip).
    // Second consecutive stall → restart fresh (the resumed session is poisoned: e.g. CC
    // emits "No response requested." and ignores subsequent stdin prompts forever).
    // Reset to 0 by EventStreamHandler.onTurnSucceeded.
    @Volatile
    private var consecutivePromptStalls: Int = 0

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
                browserRenderer.hideAllStopButtons()
                browserRenderer.showPausedBanner()
                syncStreamingUi()
                statusLabel.text = "Paused — Enter to continue, type to steer, ESC to abort"
                inputArea.requestFocusInWindow()
            },
            onResume = { text ->
                scope.launch {
                    if (!bridge.isRunning) {
                        try {
                            clearQueuedPrompt()
                            withContext(Dispatchers.IO) {
                                bridge.restart(skills = discoveredSkills)
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
                    eventHandler.watchForTurnStartStall()
                    inputArea.text = ""
                }
            },
            onAbort = {
                clearQueuedPrompt()
                browserRenderer.hidePausedBanner()
                browserRenderer.hideThinkingIndicator()
                browserRenderer.appendHtml(renderer.renderError("Response aborted by user"))
                syncStreamingUi()
                statusLabel.text = " "
            },
            onClearPausedUi = {
                browserRenderer.hidePausedBanner()
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

        turnControlQuery.addHandler { action ->
            ApplicationManager.getApplication().invokeLater {
                when (action) {
                    "pause" -> handleEscape()
                    "stop" -> abort()
                }
            }
            JBCefJSQuery.Response("ok")
        }

        // Event stream handler: processes CLI events from the bridge
        eventHandler = EventStreamHandler(
            bridge = bridge,
            renderer = renderer,
            browserRenderer = browserRenderer,
            editReviewCoordinator = editReviewHandler.coordinator,
            taskWidget = taskWidget,
            subAgentController = subAgentController,
            goalController = goalController,
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
            onTurnStartStalled = { recoverStalledPromptTurn() },
            onToolResultStalled = { recoverStalledToolTurn() },
            isUserInputPending = { permissionDispatcher.hasInFlightRequests() },
            onShowErrorNotification = { msg -> showNotification("ClawDEA", msg, NotificationType.ERROR) },
            onTurnSucceeded = { consecutivePromptStalls = 0 },
            consumeAutoAllow = { toolUseId, toolName, inputJson ->
                val signal = com.adobe.clawdea.chat.permission.AutoAllowSignal.getInstance(project)
                signal.consume(toolUseId) || signal.consume(toolName, inputJson)
            },
            isToolAutoAllowed = { toolName ->
                McpServer.getInstance(project).activeToolApprovalMode == "allow-all" &&
                    toolName != "AskUserQuestion" &&
                    !toolName.startsWith("mcp__clawdea-intellij__")
            },
            costTracker = project.getService(com.adobe.clawdea.cost.CostTracker::class.java),
            resolveEffort = {
                ClawDEASettings.getInstance()
                    .getSelectedEffort(project.basePath ?: "")
            },
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
            onRestartAfterTerminal = { sessionId ->
                scope.launch {
                    try {
                        turnController.resetTurnState()
                        clearQueuedPrompt()
                        syncStreamingUi()
                        browserRenderer.hidePausedBanner()
                        browserRenderer.hideThinkingIndicator()
                        browserRenderer.hideAllStopButtons()
                        withContext(Dispatchers.IO) { bridge.restart(resumeSessionId = sessionId, skills = discoveredSkills) }
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

        // Register this panel as a permission router so the project-level
        // McpServer can route `request_permission` calls to whichever panel
        // actually emitted the matching ToolUse — not whichever panel happens
        // to be focused. The router's claim() consults EventStreamHandler's
        // recent-ToolUse map, which is panel-local.
        PermissionRouterRegistry.getInstance(project).register(
            router = object : PermissionRouter {
                override fun claimById(toolUseId: String): Boolean =
                    eventHandler.claimPermissionById(toolUseId)

                override fun claim(toolName: String, inputJson: String): String? =
                    eventHandler.claimPermission(toolName, inputJson)
            },
            dispatcher = permissionDispatcher,
        )

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

        // Wiki-git-state banner action bridge: fix / dismiss clicks
        wikiGitStateActionQuery.addHandler { action ->
            log.info("wiki-git-state action click received: action=$action")
            ApplicationManager.getApplication().invokeLater {
                wikiGitStateBanner.handleAction(action)
            }
            JBCefJSQuery.Response("ok")
        }

        // Slash-command link bridge: lets in-message links run a slash command
        // verbatim through the standard send pipeline (queueing, expansion,
        // dispatch). The JS side reads `data-slash` from the clicked element
        // and passes its value here unchanged.
        runSlashCommandQuery.addHandler { slash ->
            if (!slash.isNullOrBlank() && slash.startsWith("/")) {
                ApplicationManager.getApplication().invokeLater { submitCommand(slash) }
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
                // One-line summary note after wiki-author auto-applies (Task 11).
                // Skip when nothing was applied or the auto-update setting is off.
                if (applied.isNotEmpty() && ClawDEASettings.getInstance().state.autoUpdateWiki) {
                    val acted = applied.size
                    // Reference the actual wiki location; team mode moves the wiki
                    // (and its drift-state file) out of .clawdea/wiki/.
                    val wikiRel = run {
                        val wikiDir = com.adobe.clawdea.knowledge.wiki.WikiLocator.getInstance(project).wikiDir()
                        val base = project.basePath
                        if (base != null) {
                            runCatching { java.nio.file.Paths.get(base).relativize(wikiDir).toString() }
                                .getOrDefault(".clawdea/wiki")
                        } else {
                            ".clawdea/wiki"
                        }
                    }
                    val msg = "Auto-applied wiki updates from drift events: $acted acted on. " +
                        "See the drift-state file under `$wikiRel/` for details."
                    appendHtml(renderer.renderInfoMessage(msg))
                }
            }
            // Both /wiki-relocate and /seed-wiki call DriftDetectionService.rescan()
            // after writing config / gitignore — piggyback on that signal to
            // re-evaluate the wiki-git-state contract without an extra plumbing path.
            scope.launch(Dispatchers.IO) { refreshWikiGitStateBanner() }
        }
        // Initial render based on current state (StartupActivity may have already run rescan).
        ApplicationManager.getApplication().invokeLater {
            driftBanner.setEvents(driftService.current())
        }

        // One-shot wiki-git-state check at chat start. Off-EDT; the banner update
        // hops back to EDT inside refreshWikiGitStateBanner.
        scope.launch(Dispatchers.IO) {
            refreshWikiGitStateBanner()
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

        // Chat-view freeze recovery: detect JVM suspend gaps + display
        // configuration changes and recover the JCEF compositor.
        scope.launch(Dispatchers.Default) {
            var lastTickAt = System.currentTimeMillis()
            var lastDisplay: ChatViewHealthMonitor.DisplaySnapshot? = null
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

                // Display configuration change recovery (issue #36): when the
                // user (un)plugs an external monitor or drags the IDE window
                // between displays with different DPI, JCEF's OSR surface
                // keeps its old screen info and renders at the wrong scale
                // ("half resolution") until something forces a re-acquire.
                // We sample the current GraphicsConfiguration here and kick
                // the compositor whenever it changes. Reading
                // graphicsConfiguration / its device + transform is
                // thread-safe and cheap enough to run from the heartbeat
                // dispatcher without bouncing through the EDT.
                val snapshot = runCatching { sampleDisplay() }.getOrNull()
                if (snapshot != null && ChatViewHealthMonitor.isDisplayChanged(lastDisplay, snapshot)) {
                    log.info(
                        "view-health: display config changed " +
                            "(was=$lastDisplay, now=$snapshot), kicking compositor",
                    )
                    ApplicationManager.getApplication().invokeLater { browserRenderer.forceRedraw() }
                }
                if (snapshot != null) lastDisplay = snapshot
            }
        }
    }

    /**
     * Snapshot the [java.awt.GraphicsConfiguration] of the chat browser
     * component for comparison across heartbeat ticks. Returns null when the
     * component is not yet displayable (e.g. during early IDE startup or
     * while the tool window is hidden) so we don't trigger spurious "display
     * changed" recoveries on the first few ticks.
     */
    private fun sampleDisplay(): ChatViewHealthMonitor.DisplaySnapshot? {
        val gc = browser.component.graphicsConfiguration ?: return null
        val tx = gc.defaultTransform
        return ChatViewHealthMonitor.DisplaySnapshot(
            deviceId = gc.device.iDstring,
            scaleX = tx.scaleX,
            scaleY = tx.scaleY,
            bounds = gc.bounds,
        )
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
        val mcpServer = McpServer.getInstance(project)
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
        }
        fun syncRendererAutoAccept() {
            val effective = mcpServer.activeAutoAcceptEdits || ToolApprovalModeUi.isAllowAll(mcpServer.activeToolApprovalMode)
            renderer.autoAcceptEdits = effective
        }
        var activeApprovalKey = settings.toolApprovalMode
        val approvalCombo = ComboBox(ToolApprovalModeUi.comboBoxModel()).apply {
            font = font.deriveFont(11f)
            selectedIndex = ToolApprovalModeUi.indexForKey(settings.toolApprovalMode)
            toolTipText = ToolApprovalModeUi.TOOLTIP_TEXT
            ToolApprovalModeUi.installRenderer(this)
            addActionListener {
                val previousKey = activeApprovalKey
                val newKey = ToolApprovalModeUi.keyForIndex(selectedIndex)
                if (!ToolApprovalModeUi.requiresCliRestart(previousKey, newKey)) return@addActionListener
                activeApprovalKey = newKey
                mcpServer.activeToolApprovalMode = newKey
                syncRendererAutoAccept()
                applyToolApprovalModeChange(ToolApprovalModeUi.labelForKey(newKey))
            }
        }
        val autoAcceptEditsCheck = JCheckBox("Auto-accept edits").apply {
            font = font.deriveFont(11f)
            isSelected = settings.autoAcceptEdits
            toolTipText = "Apply edits without showing the diff dialog. Every edit still leaves a clickable diff link in the chat so you can review and revert."
            addActionListener {
                mcpServer.activeAutoAcceptEdits = isSelected
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

    private fun applyToolApprovalModeChange(label: String) {
        when {
            bridge.isRunning && !turnController.isStreaming -> restartAfterToolApprovalModeChange(label)
            bridge.isRunning -> {
                appendHtml(renderer.renderInfoMessage("Tool approval changed to $label. It will apply after the current response or next session restart."))
            }
            else -> {
                appendHtml(renderer.renderInfoMessage("Tool approval changed to $label. It will apply on the next send."))
            }
        }
    }

    private fun restartAfterToolApprovalModeChange(label: String) {
        scope.launch {
            try {
                clearQueuedPrompt()
                withContext(Dispatchers.IO) { bridge.restart(skills = discoveredSkills) }
                appendHtml(renderer.renderInfoMessage("Tool approval changed to $label. Session restarted to apply permission flags."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendHtml(renderer.renderError("Failed to restart session after changing tool approval: ${e.message}"))
            }
        }
    }

    private fun recoverStalledToolTurn() {
        // Tool-result stalls are usually one-off network glitches in the middle of an
        // otherwise healthy session; resuming preserves all prior context. Don't count
        // these toward the prompt-stall escalation — they're a different failure mode.
        recoverStalledTurn(
            "Claude CLI stopped responding after a tool completed. Restarting the session; please retry the last prompt.",
            fresh = false,
        )
    }

    private fun recoverStalledPromptTurn() {
        consecutivePromptStalls += 1
        val escalateToFresh = shouldEscalateToFreshRestart(consecutivePromptStalls)
        recoverStalledTurn(stalledPromptMessage(escalateToFresh), fresh = escalateToFresh)
    }

    private fun recoverStalledTurn(message: String, fresh: Boolean) {
        appendHtml(renderer.renderError(message))
        turnController.resetTurnState()
        clearQueuedPrompt()
        syncStreamingUi()
        browserRenderer.hideThinkingIndicator()
        browserRenderer.hideAllStopButtons()
        statusLabel.text = " "

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (fresh) {
                        bridge.restartFresh(skills = discoveredSkills)
                    } else {
                        bridge.restart(skills = discoveredSkills)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = "Failed to restart Claude CLI after a stalled turn: ${e.message}"
                appendHtml(renderer.renderError(msg))
                showNotification("ClawDEA", msg, NotificationType.ERROR)
            }
        }
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
        val controlsState = TurnControlsState.from(
            isStreaming = turnController.isStreaming,
            isPaused = turnController.isPaused,
        )
        modelCombo.isEnabled = controlsState.selectorsEnabled
        effortCombo.isEnabled = controlsState.selectorsEnabled
        browserRenderer.updateTurnControlButton(controlsState.thinkingButton)
        if (controlsState.thinkingButton == TurnControlButton.NONE) {
            browserRenderer.hideThinkingIndicator()
            browserRenderer.hideAllStopButtons()
        }
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
        val costChip = com.adobe.clawdea.cost.CostChip(project, this)
        westRow.add(costChip)
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
        // Use CC's reported contextWindow when available — Opus 4.7 is 1M, Sonnet 4.6 is
        // 200K. Hardcoding 200K pegged the indicator at 100% on Opus 4.7 because the
        // primer + skills system prompt alone consumes ~200K. Default to 200K only until
        // the first `result` event lands so we have something sensible to render.
        val budget = if (eventHandler.contextWindow > 0) eventHandler.contextWindow else DEFAULT_CONTEXT_WINDOW_TOKENS
        val pct = ((eventHandler.totalTokensUsed.toLong() * 100) / budget).toInt().coerceAtMost(100)
        contextLabel.text = "context: $pct% used"
    }

    private fun buildCommandContext(): CommandContext {
        return CommandContext(
            appendHtml = { html -> appendHtml(html) },
            showNotification = { msg -> showNotification("ClawDEA", msg, NotificationType.INFORMATION) },
            askQuestion = { input, onResolve ->
                val service = HandlerQuestionService.getInstance(project)
                val requestId = service.register(onResolve)
                appendHtml(askUserQuestionRenderer.renderCard(requestId, input))
            },
            dispatchToBridge = { text -> dispatchSendToBridge(text, renderInChat = false) },
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
            // Kick the JCEF compositor first (issue #36): on its own, a page
            // reload only paints once and then the surface freezes again
            // because the post-wake CEF rendering loop never recovers.
            browserRenderer.forceRedraw()
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

        commandRegistry.register("/goal", BridgeForwardHandler(
            CommandInfo("/goal", "Set a completion condition Claude works toward across turns", CommandCategory.BRIDGE),
        ))

        commandRegistry.register("/note", com.adobe.clawdea.commands.handlers.NoteAppendHandler(project, scope))

        commandRegistry.register("/promote-to-wiki", com.adobe.clawdea.commands.handlers.PromoteToWikiHandler.create(project))

        commandRegistry.register("/profile", com.adobe.clawdea.profiling.commands.ProfileCommandHandler.create())

        commandRegistry.register("/learn", com.adobe.clawdea.commands.handlers.BridgeExpandingHandler(
            CommandInfo("/learn", "Propose a wiki page from the current conversation", CommandCategory.BRIDGE),
        ) { args ->
            val topic = args.trim()
            val opening = if (topic.isEmpty()) {
                "Pick the most useful insight from our recent conversation worth filing as a wiki page."
            } else {
                "Draft a wiki page about \"$topic\" based on our recent conversation."
            }
            // Resolve the actual wiki location (team mode moves it out of
            // .clawdea/wiki/, e.g. docs/llm-wiki/). Hardcoding .clawdea/wiki/ here
            // would send propose_write to a path that doesn't exist in team-mode
            // repos and the page would never appear in the real wiki.
            val wikiRel = run {
                val wikiDir = com.adobe.clawdea.knowledge.wiki.WikiLocator.getInstance(project).wikiDir()
                val base = project.basePath
                if (base != null) {
                    runCatching { java.nio.file.Paths.get(base).relativize(wikiDir).toString() }
                        .getOrDefault(".clawdea/wiki")
                } else {
                    ".clawdea/wiki"
                }
            }
            val targetPath = if (topic.isEmpty()) "$wikiRel/concepts/<kebab-case-name>.md" else "$wikiRel/concepts/$topic.md"
            val linkTarget = if (topic.isEmpty()) {
                "[Concept](concepts/<kebab-case-name>.md)"
            } else {
                val title = topic.split('-', ' ')
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { it.replaceFirstChar { char -> char.titlecase() } }
                "[$title](concepts/$topic.md)"
            }
            val invariantTemplate = com.adobe.clawdea.knowledge.prompts.PromptResource.load("wiki-page-invariant")
            val navigationTemplate = com.adobe.clawdea.knowledge.prompts.PromptResource.load("wiki-page-navigation")
            """
            $opening

            **CRITICAL:** Use the **propose_write** MCP tool (registered as `mcp__clawdea-intellij__propose_write`).
            The built-in `Write` tool is DISABLED in this environment — calls to it are silently rejected and no file is created.
            If a tool result reports "tool not allowed" or you see an "Unavailable" status, you used the wrong tool;
            retry with propose_write.

            Before writing, **classify the concept** into one of three categories:
            - `pipeline` — multi-step resolution with cache boundaries or registration order that a reasoner could get wrong
              (e.g. content policy resolution, dispatcher cache invalidation, Sling servlet dispatching).
            - `runtime-behavior` — non-trivial runtime semantics where invariants must hold
              (e.g. OSGi service registration timing, JCR observation, login token TTLs, feature toggle propagation).
            - `navigation` — flat subsystem where a reader mainly needs to locate the right files
              (e.g. a utility package, a CLI entry point, a simple renderer).

            **For `pipeline` or `runtime-behavior` concepts**, use the INVARIANT-FIRST template below.
            The invariants section is load-bearing — it must anchor runtime reasoning, not just point at files.

            ----- BEGIN INVARIANT-FIRST TEMPLATE -----
            $invariantTemplate
            ----- END INVARIANT-FIRST TEMPLATE -----

            **For `navigation` concepts**, use the NAVIGATION template below.

            ----- BEGIN NAVIGATION TEMPLATE -----
            $navigationTemplate
            ----- END NAVIGATION TEMPLATE -----

            Use propose_write to create $targetPath using the template you picked. Fill in every section;
            for `pipeline` or `runtime-behavior` pages, produce 3–7 invariants, each citing the file that makes it true.

            Then use propose_edit (NOT the built-in Edit tool) to update $wikiRel/index.md so it links $linkTarget,
            unless that link is already there.
            """.trimIndent()
        })

        commandRegistry.register("/seed-wiki", com.adobe.clawdea.commands.handlers.SeedWikiHandler(project) { wikiPathRel ->
            val invariantTemplate = com.adobe.clawdea.knowledge.prompts.PromptResource.load("wiki-page-invariant")
            val navigationTemplate = com.adobe.clawdea.knowledge.prompts.PromptResource.load("wiki-page-navigation")
            val capWording = if (ClawDEASettings.getInstance().state.enableWikiLibrarian) {
                "all concept areas worth documenting (main subsystems, key APIs, active feature work, architectural decisions worth capturing). Err on the side of more focused pages over fewer dense ones — there is no upper bound. A 200-file project might have 5 concepts; a 5,000-file project might have 50."
            } else {
                "5–10 concept areas worth documenting (main subsystems, key APIs, active\n               feature work, architectural decisions worth capturing)."
            }
            """
            Bootstrap an initial wiki for this project at $wikiPathRel/.

            **CRITICAL — TOOL CHOICE:** Use the **propose_write** MCP tool (registered as
            `mcp__clawdea-intellij__propose_write`) for every file you create. The built-in `Write` tool is
            DISABLED in this environment — calls to it are silently rejected and no file is created.
            If a tool result reports "tool not allowed" or you see an "Unavailable" status, you used the
            wrong tool; retry with propose_write.

            **Scope rule (avoid overlap with CLAUDE.md):** the wiki holds subsystem-specific knowledge —
            concept pages with invariants, resolution pipelines, source pointers, and anti-patterns specific
            to one part of the codebase. The root `CLAUDE.md` holds project-wide context: build/test
            commands, the high-level architecture (3–4 paragraphs max), and repo-wide
            conventions/invariants. Do NOT duplicate build commands, top-level architecture, or repo-wide
            conventions in wiki pages — they belong in `CLAUDE.md` and would drift if duplicated. Do NOT
            put detailed subsystem documentation in `CLAUDE.md` — it belongs in concept pages and would
            bloat the primer if duplicated.

            Steps:
            1. **Bootstrap `CLAUDE.md` if missing.** Check whether `CLAUDE.md` exists at the project
               root.

               **If it does NOT exist**, create it via `propose_write` using the template below.
               Discover real values for each section from `package.json` scripts, `pom.xml` profiles,
               `build.gradle.kts` tasks, `Makefile` targets, and the project README; do not ship the
               italicised placeholder text.

               ```markdown
               # CLAUDE.md

               This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

               ## Build & Test

               _(List the build/test/run commands the human dev typically uses. Discover them from
               package.json scripts, pom.xml profiles, build.gradle.kts tasks, Makefile targets, or
               README quick-start sections. Match the developer voice — concise, command-first.)_

               ## Architecture

               _(One short paragraph about the high-level shape of the project: the entry point, the
               main subsystems, how data flows between them. **No more than 3–4 paragraphs.**
               Detailed subsystem documentation belongs in the wiki, not here.)_

               ## Key conventions

               _(Bulleted list of repo-wide invariants — language/JVM target, dependency-management
               quirks, anti-patterns to avoid, file-organisation rules, and anything else that
               applies project-wide and would be costly to discover by reading sources. Do NOT
               duplicate subsystem-specific knowledge here — that goes in the wiki.)_

               ## Wiki

               Detailed knowledge about individual subsystems lives in [`$wikiPathRel/index.md`]($wikiPathRel/index.md).
               Concept pages cover entry points, invariants, and source pointers per subsystem. Read
               the wiki page for a subsystem before grepping its source files.
               ```

               **If `CLAUDE.md` already exists**, do NOT overwrite it. Check whether it already
               contains a markdown link whose target lives under the wiki root (i.e. a link to
               `$wikiPathRel/index.md`, or any other path beginning with `$wikiPathRel/`). If such a
               link is missing, append a new section at the end of the file via `propose_edit`:

               ```markdown

               ## Wiki

               Detailed knowledge about individual subsystems lives in [`$wikiPathRel/index.md`]($wikiPathRel/index.md).
               Concept pages cover entry points, invariants, and source pointers per subsystem. Read
               the wiki page for a subsystem before grepping its source files.
               ```

               If a wiki link already exists, leave `CLAUDE.md` alone (idempotent).

            2. Read CLAUDE.md (now guaranteed to exist after step 1), README.md, and the top-level
               build files (pom.xml, package.json, build.gradle.kts) to understand the project shape.
            3. Call the get_primer MCP tool to see the auto-generated REPO_STATE
               (current branch, recent commits, hot files), then identify
               $capWording
            4. **Classify each concept independently** into one of:
               - `pipeline` — multi-step resolution with cache boundaries or registration order
                 a reasoner could get wrong (content policy resolution, dispatcher invalidation,
                 servlet dispatching).
               - `runtime-behavior` — non-trivial runtime semantics where invariants must hold
                 (OSGi service registration timing, JCR observation, feature toggle propagation).
               - `navigation` — flat subsystem where a reader mainly needs to locate the right files.

               For `pipeline` or `runtime-behavior` concepts, use the INVARIANT-FIRST template below.
               For `navigation` concepts, use the NAVIGATION template below.

            ----- BEGIN INVARIANT-FIRST TEMPLATE -----
            $invariantTemplate
            ----- END INVARIANT-FIRST TEMPLATE -----

            ----- BEGIN NAVIGATION TEMPLATE -----
            $navigationTemplate
            ----- END NAVIGATION TEMPLATE -----

            5. Use propose_write (NOT Write) to create:
               - $wikiPathRel/index.md — a TOC with a short intro plus standard Markdown links to each
                 concept page, e.g. `[Title](concepts/<slug>.md)`.
               - $wikiPathRel/concepts/<kebab-case-name>.md — one file per concept, using the template
                 that matches the classification. For `pipeline` or `runtime-behavior` pages, produce
                 3–7 invariants, each citing the file that makes it true.

            After I accept the diffs, $wikiPathRel/index.md will join every chat's primer automatically.
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
            handleRefreshWiki(rawArgs)
        })

        commandRegistry.register("/wiki-gap", LocalHandler(
            CommandInfo("/wiki-gap", "Show clustered wiki probe misses", CommandCategory.LOCAL),
        ) { _, ctx ->
            val basePath = project.basePath
            val misses = if (basePath != null) {
                val wikiDir = com.adobe.clawdea.knowledge.wiki.WikiLocator.getInstance(project).wikiDir()
                val projectBase = java.nio.file.Paths.get(basePath)
                com.adobe.clawdea.knowledge.drift.DriftStateStore.read(
                    wikiDir = wikiDir,
                    projectBase = projectBase,
                ).probeMisses
            } else emptyList()
            val clusters = com.adobe.clawdea.commands.handlers.WikiGapHandler.cluster(misses)
            val output = com.adobe.clawdea.commands.handlers.WikiGapHandler.formatOutput(clusters)
            ctx.appendHtml(renderer.renderInfoMessage(output))
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
        commandRegistry.register("/wiki-relocate", com.adobe.clawdea.commands.handlers.WikiRelocateHandler(project))
    }

    private sealed class RefreshWikiResult {
        data class Local(val message: String) : RefreshWikiResult()
        data class ReviewPrompt(val prompt: String) : RefreshWikiResult()
    }

    private fun handleRefreshWiki(rawArgs: String) {
        val args = RefreshWikiArgs.parse(rawArgs)
        if (args.statusOnly) {
            appendHtml(renderer.renderInfoMessage(refreshWikiStatus()))
            return
        }
        if (args.applyLowRisk && !ClawDEASettings.getInstance().state.autoUpdateWiki) {
            appendHtml(renderer.renderInfoMessage("Applying low-risk wiki drift fixes requires enabling Auto-update wiki on drift."))
            return
        }
        if (turnController.isStreaming) {
            queueExplicitPrompt(refreshWikiCommandText(rawArgs))
            appendHtml(renderer.renderInfoMessage("Queued /refresh-wiki until the current Claude turn finishes."))
            return
        }

        appendHtml(renderer.renderInfoMessage("Refreshing wiki drift..."))
        scope.launch {
            val result = withContext(Dispatchers.IO) { refreshWiki(args) }
            when (result) {
                is RefreshWikiResult.Local -> appendHtml(renderer.renderInfoMessage(result.message))
                is RefreshWikiResult.ReviewPrompt -> dispatchOrQueueRefreshPrompt(result.prompt)
            }
        }
    }

    private suspend fun refreshWiki(args: RefreshWikiArgs): RefreshWikiResult {
        if (args.applyLowRisk && !ClawDEASettings.getInstance().state.autoUpdateWiki) {
            return RefreshWikiResult.Local("Applying low-risk wiki drift fixes requires enabling Auto-update wiki on drift.")
        }

        val service = project.getService(com.adobe.clawdea.knowledge.drift.DriftDetectionService::class.java)
        val (events, _) = service.rescan()

        if (args.applyLowRisk) {
            return RefreshWikiResult.Local(formatAppliedWikiDrift(service.lastAppliedEvents()))
        }

        if (events.isEmpty()) {
            return RefreshWikiResult.Local("(no drift events detected)")
        }

        val prompt = if (ClawDEASettings.getInstance().state.enableWikiLibrarian) {
            val wikiDir = com.adobe.clawdea.knowledge.wiki.WikiLocator.getInstance(project).wikiDir()
            com.adobe.clawdea.knowledge.drift.WikiAuthorDigestBuilder.build(events, wikiDir)
        } else {
            buildLegacyRefreshWikiPrompt(events)
        }
        return RefreshWikiResult.ReviewPrompt(prompt)
    }

    private fun dispatchOrQueueRefreshPrompt(prompt: String) {
        if (turnController.isStreaming) {
            queueExplicitPrompt(prompt)
            appendHtml(renderer.renderInfoMessage("Queued wiki drift review until the current Claude turn finishes."))
            return
        }
        dispatchSendToBridge(prompt, renderInChat = false)
    }

    private fun queueExplicitPrompt(prompt: String): Boolean {
        val queued = pendingPromptController.queueExplicit(prompt)
        refreshPendingPromptStatus()
        return queued
    }

    private fun refreshWikiCommandText(rawArgs: String): String {
        val args = rawArgs.trim()
        return if (args.isEmpty()) "/refresh-wiki" else "/refresh-wiki $args"
    }

    private fun refreshWikiStatus(): String {
        val basePath = project.basePath ?: return "Wiki drift status: project path unavailable."
        val wikiDir = com.adobe.clawdea.knowledge.wiki.WikiLocator.getInstance(project).wikiDir()
        val projectBase = java.nio.file.Paths.get(basePath)
        val state = com.adobe.clawdea.knowledge.drift.DriftStateStore.read(
            wikiDir = wikiDir,
            projectBase = projectBase,
        )
        val service = project.getService(com.adobe.clawdea.knowledge.drift.DriftDetectionService::class.java)
        return RefreshWikiStatusFormatter.format(
            RefreshWikiStatus(
                lastRunAt = state.lastScanAt,
                pendingEventTypes = service.current().map { refreshWikiEventName(it) },
            ),
        )
    }

    private fun formatAppliedWikiDrift(events: List<com.adobe.clawdea.knowledge.drift.DriftEvent>): String {
        if (events.isEmpty()) return "No low-risk wiki drift fixes were applied."
        val summary = events.joinToString(", ") { event ->
            val icon = com.adobe.clawdea.knowledge.drift.DriftEventIcon.iconFor(event)
            "$icon ${refreshWikiEventName(event)} ${refreshWikiEventTarget(event)}"
        }
        return "Applied ${events.size} low-risk wiki drift fix(es): $summary."
    }

    private fun refreshWikiEventName(event: com.adobe.clawdea.knowledge.drift.DriftEvent): String =
        when (event) {
            is com.adobe.clawdea.knowledge.drift.DriftEvent.CodeRename -> "CodeRename"
            is com.adobe.clawdea.knowledge.drift.DriftEvent.ManifestStale -> "ManifestStale"
            is com.adobe.clawdea.knowledge.drift.DriftEvent.CommitDrift -> "CommitDrift"
            is com.adobe.clawdea.knowledge.drift.DriftEvent.WikiSuggestion -> "WikiSuggestion(${event.kind.name})"
        }

    private fun refreshWikiEventTarget(event: com.adobe.clawdea.knowledge.drift.DriftEvent): String =
        when (event) {
            is com.adobe.clawdea.knowledge.drift.DriftEvent.CodeRename -> event.wikiPage.fileName.toString()
            is com.adobe.clawdea.knowledge.drift.DriftEvent.ManifestStale -> event.repoKey
            is com.adobe.clawdea.knowledge.drift.DriftEvent.CommitDrift -> event.wikiPage.fileName.toString()
            is com.adobe.clawdea.knowledge.drift.DriftEvent.WikiSuggestion -> event.title
        }

    private fun buildLegacyRefreshWikiPrompt(events: List<com.adobe.clawdea.knowledge.drift.DriftEvent>): String {
        val sb = StringBuilder()
        sb.appendLine("The following drift events were detected. Review each and apply fixes via `propose_edit` or `propose_write`:")
        sb.appendLine()
        for (event in events) {
            val icon = com.adobe.clawdea.knowledge.drift.DriftEventIcon.iconFor(event)
            when (event) {
                is com.adobe.clawdea.knowledge.drift.DriftEvent.CodeRename -> {
                    sb.appendLine("- $icon **CodeRename** in `${event.wikiPage.fileName}`")
                    sb.appendLine("  - broken link: `${event.brokenLink}`")
                    if (event.suggestedReplacement != null) {
                        sb.appendLine("  - suggested replacement: `${event.suggestedReplacement}`")
                        sb.appendLine("  - action: confirm the replacement is the right target, then `propose_edit` `${event.wikiPage}` to update the link.")
                    } else {
                        sb.appendLine("  - no unique basename match was found; either search for the moved file via `find_files` and `propose_edit` the wiki page, or remove the broken link.")
                    }
                }
                is com.adobe.clawdea.knowledge.drift.DriftEvent.ManifestStale -> {
                    sb.appendLine("- $icon **ManifestStale** in `${event.manifestPath.fileName}` (group `${event.groupName}`)")
                    sb.appendLine("  - missing repo key: `${event.repoKey}` (line ${event.lineHint})")
                    sb.appendLine("  - action: check whether the repo moved (update path) or was deleted (`propose_edit` the manifest to comment out or remove the bullet).")
                }
                is com.adobe.clawdea.knowledge.drift.DriftEvent.CommitDrift -> {
                    // Should not appear when enableWikiLibrarian=false (the detector is gated),
                    // but render minimally for safety.
                    sb.appendLine("- $icon **CommitDrift** in `${event.wikiPage.fileName}`")
                    sb.appendLine("  - commits: ${event.commitShas.joinToString(", ")}")
                    sb.appendLine("  - touched paths: ${event.touchedPaths.joinToString(", ")}")
                }
                is com.adobe.clawdea.knowledge.drift.DriftEvent.WikiSuggestion -> {
                    sb.appendLine("- $icon **WikiSuggestion (${event.kind.name})**: ${event.title}")
                    sb.appendLine("  - rationale: ${event.rationale}")
                    sb.appendLine("  - target files: ${event.targetFiles.joinToString(", ")}")
                    if (event.sourcePage != null) {
                        sb.appendLine("  - observed while reading: ${event.sourcePage}")
                    }
                    sb.appendLine("  - action: review the suggested change. If you agree, draft the wiki update via `propose_write` or `propose_edit`. If not, dismiss.")
                }
            }
        }
        sb.appendLine()
        sb.appendLine("After fixing each event, the user accepts/rejects via the diff dialog as usual.")
        return sb.toString()
    }

    fun suggestSeedWikiIfMissing() = sessionManager.suggestSeedWikiIfMissing()

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
        maybeHandleCorrection(text)
        appendHtml(renderer.renderUserMessage(text))
        browserRenderer.showThinkingIndicator()
        bridge.sendMessage(text)
        turnController.onUserSend()
        syncStreamingUi()
        eventHandler.turnHasContent = false
        eventHandler.streamStartTime = System.currentTimeMillis()
        statusLabel.text = "Claude is thinking..."
        eventHandler.watchForTurnStartStall()
    }

    private fun maybeHandleCorrection(userMessage: String) {
        if (userMessage.trim().startsWith("/")) return
        val prior = eventHandler.lastAssistantText
        val signal = com.adobe.clawdea.knowledge.corrections.CorrectionDetector.detect(userMessage, prior)
            ?: return
        val contextHash = project.basePath?.hashCode()?.toUInt()?.toString(16) ?: "unknown"
        project.getService(com.adobe.clawdea.knowledge.drift.DriftDetectionService::class.java)
            .recordUserCorrection(signal.userMessage, contextHash)
        appendHtml(renderer.renderInfoMessage(
            "Correction detected. If this reveals a missing wiki insight, run: /learn ${signal.suggestedTopic}"
        ))
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

    fun submitCommand(text: String) {
        sendTextThroughNormalRouting(text)
    }

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
            // /goal must act immediately — especially `/goal clear`/`/goal stop`.
            // Queuing it behind a long-running goal turn would defer it (and join
            // it with other queued text), which is how a malformed
            // "clear\n/goal clear" goal gets created. Route it now; the clear
            // path interrupts the loop, the set path replaces it cleanly.
            if (handler?.info?.name == "/goal") {
                inputArea.text = ""
                sendTextThroughNormalRouting(text)
                return
            }
            if (handler?.info?.name == "/refresh-wiki") {
                val refreshArgs = RefreshWikiArgs.parse(match.args)
                val localOnly = refreshArgs.statusOnly ||
                    (refreshArgs.applyLowRisk && !ClawDEASettings.getInstance().state.autoUpdateWiki)
                if (!localOnly) {
                    queueCurrentComposerText()
                    return
                }
            }
            if (handler is BridgeForwardHandler ||
                handler is com.adobe.clawdea.commands.handlers.BridgeExpandingHandler ||
                handler is com.adobe.clawdea.commands.handlers.SeedWikiHandler ||
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
                if (handler.info.name == "/goal") {
                    // /goal is fully owned by handleGoalCommand (banner state +
                    // the forward/abort decision), so it must not also run
                    // BridgeForwardHandler.execute or fall through to the verbatim
                    // forward below.
                    handleGoalCommand(match.args)
                    return
                }
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

    /**
     * Fully handle a `/goal` command: update ClawDEA's banner state, then get the
     * command to the CLI. `/goal` does NOT go through BridgeForwardHandler's
     * verbatim forward — this method owns it so a running goal is stopped correctly.
     *
     * A goal's auto-continue is one long streaming turn. The only reliable way to
     * stop it is to interrupt that turn — exactly what Esc→Stop ([abort]) does.
     * Crucially we must NOT start any follow-up turn afterward: a message sent
     * right after the interrupt never returns a result in this CLI mode, which
     * left the thinking indicator stuck. So a `/goal` issued mid-stream simply
     * interrupts (and, for a replace, asks the user to re-issue once idle).
     * Idle `/goal …` is forwarded normally.
     */
    private fun handleGoalCommand(args: String) {
        val trimmed = args.trim()
        val streaming = turnController.isStreaming || turnController.isPaused
        val isClear = trimmed.lowercase() in GOAL_CLEAR_ALIASES

        if (streaming) {
            // A bare `/goal` status query mid-loop shouldn't stop anything.
            if (trimmed.isEmpty()) return
            // Stop the running goal's loop the proven clean way. No follow-up turn.
            goalController.onClear()
            browserRenderer.hideGoalBanner()
            stopStreamingTurnQuietly()
            if (!isClear) {
                appendHtml(renderer.renderInfoMessage(
                    "Stopped the active goal. Send `/goal <condition>` again to set a new one.",
                ))
            }
            return
        }

        // Idle: forward to the CLI (set / clear / status) — clean, no interrupt.
        if (isClear) {
            goalController.onClear()
            browserRenderer.hideGoalBanner()
        } else if (trimmed.isNotEmpty()) {
            goalController.onSet(trimmed)
            goalController.current()?.let { browserRenderer.updateGoalBanner(renderer.renderGoalBanner(it)) }
        }
        dispatchSendToBridge(if (trimmed.isEmpty()) "/goal" else "/goal $trimmed")
    }

    /**
     * Interrupt a streaming turn the way [abort] does, but without the
     * "Response aborted by user" notice — used to stop a running goal's
     * auto-continue loop when the user clears it. No follow-up turn is started.
     */
    private fun stopStreamingTurnQuietly() {
        if (!turnController.isStreaming && !turnController.isPaused) return
        bridge.abort()
        turnController.resetTurnState()
        clearQueuedPrompt()
        syncStreamingUi()
        browserRenderer.hidePausedBanner()
        browserRenderer.hideThinkingIndicator()
        browserRenderer.hideAllStopButtons()
        statusLabel.text = " "
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
        if (pendingPromptController.isQueued && inputArea.text.isBlank() && !pendingPromptController.hasExplicitPrompt) {
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
        eventHandler.watchForTurnStartStall()

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
        if (!turnController.isStreaming && !turnController.isPaused) return
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
            // Try filename-based fallback: derive ClassName.kt / ClassName.java from the path
            val classNameForFile = path.substringAfterLast(".").removeSuffix("()")
                .takeIf { it.firstOrNull()?.isUpperCase() == true }
                ?: path.takeIf { it.firstOrNull()?.isUpperCase() == true && !it.contains(".") }
            if (classNameForFile != null) {
                val scope = GlobalSearchScope.projectScope(project)
                val candidateExtensions = LanguageSupportRegistry.all()
                    .flatMap { it.fileExtensions }
                    .ifEmpty { listOf("kt", "java") }
                val fallbackFile = runReadAction {
                    candidateExtensions.asSequence()
                        .map { ext -> "$classNameForFile.$ext" }
                        .flatMap { name -> FilenameIndex.getVirtualFilesByName(name, scope).asSequence() }
                        .sortedBy { navPriority(it.path) }
                        .firstOrNull()
                }
                if (fallbackFile != null) {
                    resolved = true
                    ApplicationManager.getApplication().invokeLater {
                        OpenFileDescriptor(project, fallbackFile, line, col).navigate(true)
                        if (parsed.isRange) selectLineRange(fallbackFile, parsed.startLine!!, parsed.endLine!!)
                    }
                }
            }
        }

        if (!resolved) {
            val searchText = path.substringAfterLast(".").removeSuffix("()")
                .ifBlank { path }
            ApplicationManager.getApplication().invokeLater {
                val action = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                    .getAction("SearchEverywhere") ?: return@invokeLater
                val manager = com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
                    .getInstance(project)
                if (manager.isShown) return@invokeLater
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
                manager.show(com.intellij.ide.actions.searcheverywhere.SearchEverywhereManagerImpl.ALL_CONTRIBUTORS_GROUP_ID, searchText, event)
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

    /**
     * Re-runs the wiki-git-state check and updates the banner. Safe to call from
     * any thread — the check itself is off-EDT (git4idea), the banner update is
     * marshalled onto EDT.
     *
     * Called once at chat-init and whenever a command (e.g. `/wiki-relocate`,
     * `/seed-wiki`) might have changed the tracked/ignored status of one of the
     * three checked files.
     */
    fun refreshWikiGitStateBanner() {
        if (wikiGitStateBannerDismissed) return
        val issues = try {
            com.adobe.clawdea.knowledge.wiki.WikiGitStateChecker.getInstance(project).check()
        } catch (e: Throwable) {
            log.warn("WikiGitStateChecker failed: ${e.message}")
            emptyList()
        }
        ApplicationManager.getApplication().invokeLater {
            wikiGitStateBanner.setIssues(issues)
        }
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
        PermissionRouterRegistry.getInstance(project).unregister(permissionDispatcher)
        if (::driftListenerUnregister.isInitialized) {
            try { driftListenerUnregister() } catch (_: Throwable) {}
        }
        abortQuery.dispose()
        turnControlQuery.dispose()
        openDiffQuery.dispose()
        editActionQuery.dispose()
        healthQuery.dispose()
        openFileQuery.dispose()
        navigateQuery.dispose()
        permissionDecisionQuery.dispose()
        driftActionQuery.dispose()
        runSlashCommandQuery.dispose()
        wikiGitStateActionQuery.dispose()
        browser.dispose()
    }

    companion object {
        // Fallback context window before the first `result` event tells us the real
        // value (200K Sonnet vs 1M Opus 4.7). The CLI's auto-compaction kicks in
        // around ~80% of the actual window.
        private const val DEFAULT_CONTEXT_WINDOW_TOKENS = 200_000

        private val GOAL_CLEAR_ALIASES = setOf("clear", "stop", "off", "reset", "none", "cancel")

        // Resume on the first prompt-start stall (transient slow-first-byte / network blip).
        // Escalate to a fresh restart on the second consecutive stall — at that point the
        // resumed session is poisoned and resuming it again will just stall the same way.
        internal fun shouldEscalateToFreshRestart(consecutivePromptStalls: Int): Boolean =
            consecutivePromptStalls >= 2

        internal fun stalledPromptMessage(escalateToFresh: Boolean): String =
            if (escalateToFresh) {
                "Claude CLI is unresponsive even after a resume — the session looks stuck. Starting a fresh session; please retry the last prompt."
            } else {
                "Claude CLI did not respond after receiving the prompt. Restarting the session; please retry the last prompt."
            }
    }
}
