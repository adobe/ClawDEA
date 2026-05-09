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
package com.adobe.clawdea.debug

import com.adobe.clawdea.util.runReadAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.*
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.frame.*
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Key
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// --- Result data types (pure Kotlin, no IDE dependency) ---

data class SessionStatus(
    val active: Boolean,
    val suspended: Boolean,
    val file: String?,
    val line: Int,
    val method: String?,
    val sessionType: String?,
) {
    fun toText(): String {
        if (!active) return if (method != null) method else "No debug session active."
        val pos = if (file != null) " at $file:$line${method?.let { " ($it)" } ?: ""}" else ""
        val state = if (suspended) "suspended" else "running"
        return "Session active ($sessionType), $state$pos"
    }

    companion object {
        val NONE = SessionStatus(false, false, null, -1, null, null)
    }
}

data class FrameInfo(
    val index: Int,
    val file: String?,
    val line: Int,
    val method: String?,
    val className: String?,
) {
    fun toText(): String {
        val loc = file?.let { "$it:$line" } ?: "unknown"
        val name = method ?: "?"
        val cls = className?.let { "$it." } ?: ""
        return "#$index $cls$name ($loc)"
    }
}

data class VariableInfo(
    val name: String,
    val type: String?,
    val value: String,
    val expandable: Boolean,
) {
    fun toText(): String {
        val typeStr = type?.let { " : $it" } ?: ""
        val expandStr = if (expandable) " [expandable]" else ""
        return "$name$typeStr = $value$expandStr"
    }
}

data class EvalResult(
    val type: String?,
    val value: String,
    val expandable: Boolean = false,
    val isError: Boolean = false,
) {
    fun toText(): String {
        if (isError) return "Error: $value"
        val typeStr = type?.let { "($it) " } ?: ""
        val expandStr = if (expandable) " [expandable]" else ""
        return "$typeStr$value$expandStr"
    }

    companion object {
        fun error(message: String) = EvalResult(null, message, isError = true)
    }
}

data class BreakpointInfo(
    val file: String,
    val line: Int,
    val enabled: Boolean,
    val claudeOwned: Boolean,
    val condition: String?,
    val logExpression: String?,
) {
    fun toText(): String {
        val owner = if (claudeOwned) "claude" else "user"
        val state = if (enabled) "enabled" else "disabled"
        val cond = condition?.let { " condition=\"$it\"" } ?: ""
        val log = logExpression?.let { " log=\"$it\"" } ?: ""
        return "$file:$line [$owner, $state$cond$log]"
    }
}

// --- Service ---

@Service(Service.Level.PROJECT)
class DebugBridge(private val project: Project) : Disposable {

    private val log = Logger.getInstance(DebugBridge::class.java)
    val breakpointTracker = BreakpointTracker()
    val suspendGate = SuspendGate()
    private val steppingLock = ReentrantLock()

    private var activeSession: XDebugSession? = null
    private var sessionType: String? = null
    private var lastComputedValues: Map<String, XValue> = emptyMap()

    companion object {
        val CLAUDE_OWNED_KEY = Key.create<Boolean>("clawdea.debug.owned")
        private val STEP_TIMEOUT = Duration.ofSeconds(30)
        private val RESUME_TIMEOUT = Duration.ofSeconds(10)

        fun getInstance(project: Project): DebugBridge =
            project.getService(DebugBridge::class.java)
    }

    // --- Session lifecycle ---

    fun launch(configName: String): SessionStatus {
        if (activeSession != null) return errorStatus("A debug session is already active. Stop it first.")

        val runManager = RunManager.getInstance(project)
        val settings = runManager.allSettings.find { it.name == configName }
            ?: return errorStatus("Run configuration '$configName' not found. Available: ${runManager.allSettings.map { it.name }.joinToString(", ")}")

        return try {
            sessionType = "config"
            startDebugSession(settings)
        } catch (e: Exception) {
            errorStatus("Failed to launch debug session: ${e.message}")
        }
    }

    fun launchAdHoc(type: AdHocType, target: String, args: String?, env: Map<String, String>): SessionStatus {
        if (activeSession != null) return errorStatus("A debug session is already active. Stop it first.")

        if ((type == AdHocType.JS_DEBUG || type == AdHocType.NODE) && !SessionLauncher.isJsSupported) {
            return errorStatus("JavaScript debugging requires IntelliJ Ultimate with the JavaScriptDebugger plugin.")
        }

        return try {
            sessionType = type.name.lowercase()
            val runManager = RunManager.getInstance(project)
            val settings = createAdHocConfig(runManager, type, target, args, env)
                ?: return errorStatus("Failed to create run configuration for type=$type target=$target")

            startDebugSession(settings)
        } catch (e: Exception) {
            errorStatus("Failed to launch ad-hoc debug session: ${e.message}")
        }
    }

    fun attach(host: String, port: Int, runtime: AttachRuntime): SessionStatus {
        if (activeSession != null) return errorStatus("A debug session is already active. Stop it first.")

        if (runtime == AttachRuntime.NODE && !SessionLauncher.isJsSupported) {
            return errorStatus("Node.js debugging requires IntelliJ Ultimate with the JavaScriptDebugger plugin.")
        }

        return try {
            sessionType = "attach_${runtime.name.lowercase()}"
            val runManager = RunManager.getInstance(project)
            val settings = createAttachConfig(runManager, host, port, runtime)
                ?: return errorStatus("Failed to create attach configuration for $runtime at $host:$port")

            startDebugSession(settings)
        } catch (e: Exception) {
            errorStatus("Failed to attach debugger: ${e.message}")
        }
    }

    fun getSession(): SessionStatus {
        val session = activeSession ?: return SessionStatus.NONE
        return buildStatus(session)
    }

    fun stop(): String {
        val session = activeSession ?: return "No debug session to stop."
        val cleanupResult = breakpointTracker.cleanup()
        performCleanup(cleanupResult)

        val stopped = CompletableFuture<Unit>()
        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Backgroundable(project, "Stopping debug session", false) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    session.stop()
                    stopped.complete(Unit)
                }
            },
        )
        try { stopped.get(10, TimeUnit.SECONDS) } catch (_: Exception) {}
        activeSession = null
        sessionType = null
        suspendGate.disarm()

        val removed = cleanupResult.claudeBreakpointsToRemove.size
        val restored = cleanupResult.userBreakpointsToReEnable.size
        return "Debug session stopped. Removed $removed Claude breakpoint(s), restored $restored user breakpoint(s)."
    }

    // --- Breakpoints ---

    fun addBreakpoint(file: String, line: Int, condition: String?, logExpression: String?): String {
        return try {
            val bpManager = XDebuggerManager.getInstance(project).breakpointManager
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(resolveAbsolutePath(file))
                ?: return "File not found: $file"

            val bpTypes = XDebuggerUtil.getInstance().getLineBreakpointTypes()
            val bpType = bpTypes.firstOrNull() ?: return "Line breakpoint type not available"

            val existingBp = bpManager.findBreakpointsAtLine(bpType, vf, line - 1).firstOrNull() as? XBreakpoint<*>
            if (existingBp != null) {
                val id = BreakpointId(file, line)
                if (breakpointTracker.isClaudeOwned(id) || breakpointTracker.isBorrowed(id)) {
                    return "Breakpoint already set at $file:$line (Claude-owned)"
                }
                val wasDisabled = !existingBp.isEnabled
                if (wasDisabled) {
                    existingBp.isEnabled = true
                }
                breakpointTracker.trackBorrowedBreakpoint(id, wasDisabled)
                return "Breakpoint set at $file:$line (reusing user breakpoint${if (wasDisabled) ", re-enabled" else ""})"
            }

            var bp: XBreakpoint<*>? = null
            ApplicationManager.getApplication().invokeAndWait {
                bp = ApplicationManager.getApplication().runWriteAction<XBreakpoint<*>?> {
                    addLineBreakpointUnchecked(bpManager, bpType, vf.url, line - 1, vf)
                }
            }

            if (bp != null) {
                bp!!.putUserData(CLAUDE_OWNED_KEY, true)
                // Condition and log expressions: API will be finalized in Task 8
                // For now, just track the breakpoint
                breakpointTracker.trackClaudeBreakpoint(BreakpointId(file, line))
                "Breakpoint set at $file:$line"
            } else {
                "Breakpoint set at $file:$line (not verified — line may not contain executable code)"
            }
        } catch (e: Exception) {
            "Failed to set breakpoint: ${e.message}"
        }
    }

    fun removeBreakpoint(file: String, line: Int): String {
        val id = BreakpointId(file, line)
        if (breakpointTracker.isBorrowed(id)) {
            val wasDisabled = breakpointTracker.untrackBorrowedBreakpoint(id)
            if (wasDisabled) {
                setBpEnabled(file, line, enabled = false)
            }
            return "Breakpoint released at $file:$line (returned to user${if (wasDisabled) ", re-disabled" else ""})"
        }
        if (!breakpointTracker.isClaudeOwned(id)) {
            return "Cannot remove user-owned breakpoint at $file:$line. Use debug_disable_breakpoint to temporarily disable it."
        }
        return removeBp(file, line, id)
    }

    fun disableBreakpoint(file: String, line: Int): String {
        return setBpEnabled(file, line, enabled = false)
    }

    fun enableBreakpoint(file: String, line: Int): String {
        return setBpEnabled(file, line, enabled = true)
    }

    fun listBreakpoints(): List<BreakpointInfo> {
        val bpManager = XDebuggerManager.getInstance(project).breakpointManager
        return bpManager.allBreakpoints.mapNotNull { bp ->
            val pos = bp.sourcePosition ?: return@mapNotNull null
            val bpFile = pos.file.path
            val bpLine = pos.line + 1
            val id = BreakpointId(bpFile, bpLine)
            BreakpointInfo(
                file = bpFile,
                line = bpLine,
                enabled = bp.isEnabled,
                claudeOwned = breakpointTracker.isClaudeOwned(id) || breakpointTracker.isBorrowed(id),
                condition = bp.conditionExpression?.expression,
                logExpression = bp.logExpressionObject?.expression,
            )
        }
    }

    // --- Execution control ---

    fun resume(): SuspendInfo? = steppingLock.withLock {
        val session = activeSession ?: return null
        suspendGate.arm()
        ApplicationManager.getApplication().invokeAndWait { session.resume() }
        suspendGate.awaitSuspend(RESUME_TIMEOUT)
    }

    fun pause(): SuspendInfo? = steppingLock.withLock {
        val session = activeSession ?: return null
        suspendGate.arm()
        ApplicationManager.getApplication().invokeAndWait { session.pause() }
        suspendGate.awaitSuspend(STEP_TIMEOUT)
    }

    fun stepOver(): SuspendInfo? = step { it.stepOver(false) }
    fun stepInto(): SuspendInfo? = step { it.stepInto() }
    fun stepOut(): SuspendInfo? = step { it.stepOut() }

    fun runToCursor(file: String, line: Int): SuspendInfo? = steppingLock.withLock {
        val session = activeSession ?: return null
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(resolveAbsolutePath(file)) ?: return null
        val position = XDebuggerUtil.getInstance().createPosition(vf, line - 1) ?: return null
        suspendGate.arm()
        ApplicationManager.getApplication().invokeAndWait { session.runToPosition(position, false) }
        suspendGate.awaitSuspend(STEP_TIMEOUT)
    }

    // --- Inspection ---

    fun getFrames(threadId: Long?): List<FrameInfo> {
        val session = activeSession ?: return emptyList()
        if (!session.isSuspended) return emptyList()
        val frame = session.currentStackFrame ?: return emptyList()
        val pos = frame.sourcePosition
        val (method, className) = extractFrameLocation(frame)
        return listOf(FrameInfo(0, pos?.file?.path, pos?.line?.plus(1) ?: -1, method, className))
    }

    fun getVariables(frameIndex: Int): List<VariableInfo> {
        val session = activeSession ?: return emptyList()
        if (!session.isSuspended) return emptyList()
        val frame = getStackFrame(session, frameIndex) ?: return emptyList()
        return computeChildren(frame)
    }

    fun expandVariable(frameIndex: Int, path: String): List<VariableInfo> {
        val session = activeSession ?: return emptyList()
        if (!session.isSuspended) return emptyList()
        val segments = path.replace("[", ".[").split(".").filter { it.isNotEmpty() }
        var currentValue = lastComputedValues[segments.firstOrNull() ?: return emptyList()] ?: return emptyList()
        for (segment in segments.drop(1)) {
            val children = computeXValueChildren(currentValue)
            val childName = segment.trimStart('[').trimEnd(']')
            currentValue = children[childName] ?: children[segment] ?: return emptyList()
        }
        return computeXValueChildren(currentValue).map { (name, xv) -> xValueToInfo(name, xv) }
    }

    fun evaluate(expression: String, frameIndex: Int): EvalResult {
        val session = activeSession ?: return EvalResult.error("No debug session active.")
        if (!session.isSuspended) return EvalResult.error("Program is running, not suspended.")
        val frame = getStackFrame(session, frameIndex)
            ?: return EvalResult.error("No stack frame at index $frameIndex.")
        val evaluator = frame.evaluator
            ?: return EvalResult.error("No evaluator available for this stack frame.")

        val xExpr = XDebuggerUtil.getInstance().createExpression(expression, null, null, com.intellij.xdebugger.evaluation.EvaluationMode.EXPRESSION)
        val valueFuture = CompletableFuture<Any>()
        ApplicationManager.getApplication().invokeLater {
            evaluator.evaluate(
                xExpr,
                object : XDebuggerEvaluator.XEvaluationCallback {
                    override fun evaluated(value: XValue) {
                        valueFuture.complete(value)
                    }
                    override fun errorOccurred(errorMessage: String) {
                        valueFuture.complete(errorMessage)
                    }
                },
                null,
            )
        }
        return try {
            when (val raw = valueFuture.get(10, TimeUnit.SECONDS)) {
                is XValue -> {
                    val info = xValueToInfo("result", raw)
                    EvalResult(info.type, info.value, info.expandable)
                }
                is String -> EvalResult.error(raw)
                else -> EvalResult.error("Unexpected evaluator result.")
            }
        } catch (_: Exception) {
            EvalResult.error("Evaluation timed out after 10 seconds.")
        }
    }

    fun setValue(frameIndex: Int, varName: String, value: String): String {
        val session = activeSession ?: return "No debug session active."
        if (!session.isSuspended) return "Program is running, not suspended."
        val xValue = lastComputedValues[varName] ?: return "Variable '$varName' not found. Call debug_get_variables first."
        val modifier = xValue.modifier ?: return "Variable '$varName' is not modifiable."

        val xExpr = XDebuggerUtil.getInstance().createExpression(value, null, null, com.intellij.xdebugger.evaluation.EvaluationMode.EXPRESSION)
        val result = CompletableFuture<String>()
        ApplicationManager.getApplication().invokeLater {
            modifier.setValue(
                xExpr,
                object : XValueModifier.XModificationCallback {
                    override fun valueModified() {
                        result.complete("Variable '$varName' set to $value")
                    }
                    override fun errorOccurred(errorMessage: String) {
                        result.complete("Failed to set '$varName': $errorMessage")
                    }
                },
            )
        }
        return try {
            result.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            "Timeout setting variable '$varName'."
        }
    }

    // --- Inspection helpers ---

    private fun computeChildren(frame: XStackFrame): List<VariableInfo> {
        val result = CompletableFuture<List<Pair<String, XValue>>>()
        ApplicationManager.getApplication().invokeLater {
            frame.computeChildren(object : XCompositeNode {
                private val collected = mutableListOf<Pair<String, XValue>>()
                override fun addChildren(children: XValueChildrenList, last: Boolean) {
                    for (i in 0 until children.size()) {
                        collected.add(children.getName(i) to children.getValue(i))
                    }
                    if (last) result.complete(collected)
                }
                @Suppress("DEPRECATION")
                override fun tooManyChildren(remaining: Int) { result.complete(collected) }
                override fun tooManyChildren(remaining: Int, addNextChildren: Runnable) { result.complete(collected) }
                override fun setAlreadySorted(alreadySorted: Boolean) {}
                override fun setErrorMessage(errorMessage: String) { result.complete(emptyList()) }
                override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) { result.complete(emptyList()) }
                override fun setMessage(message: String, icon: javax.swing.Icon?, attributes: com.intellij.ui.SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
                override fun isObsolete(): Boolean = false
            })
        }
        return try {
            val pairs = result.get(5, TimeUnit.SECONDS)
            val valueCache = mutableMapOf<String, XValue>()
            val vars = pairs.map { (name, xv) ->
                valueCache[name] = xv
                xValueToInfo(name, xv)
            }
            lastComputedValues = valueCache
            vars
        } catch (_: Exception) { emptyList() }
    }

    private fun computeXValueChildren(xValue: XValue): Map<String, XValue> {
        val collected = CompletableFuture<List<Pair<String, XValue>>>()
        ApplicationManager.getApplication().invokeLater {
            xValue.computeChildren(object : XCompositeNode {
                private val pairs = mutableListOf<Pair<String, XValue>>()
                override fun addChildren(list: XValueChildrenList, last: Boolean) {
                    for (i in 0 until list.size()) pairs.add(list.getName(i) to list.getValue(i))
                    if (last) collected.complete(pairs)
                }
                @Suppress("DEPRECATION")
                override fun tooManyChildren(remaining: Int) { collected.complete(pairs) }
                override fun tooManyChildren(remaining: Int, addNextChildren: Runnable) { collected.complete(pairs) }
                override fun setAlreadySorted(alreadySorted: Boolean) {}
                override fun setErrorMessage(errorMessage: String) { collected.complete(emptyList()) }
                override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) { collected.complete(emptyList()) }
                override fun setMessage(message: String, icon: javax.swing.Icon?, attributes: com.intellij.ui.SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
                override fun isObsolete(): Boolean = false
            })
        }
        return try {
            collected.get(5, TimeUnit.SECONDS).associate { it }
        } catch (_: Exception) { emptyMap() }
    }

    private fun xValueToInfo(name: String, xValue: XValue): VariableInfo {
        val result = CompletableFuture<VariableInfo>()
        var fullEvaluator: XFullValueEvaluator? = null
        var hasChildren = false
        var type: String? = null

        ApplicationManager.getApplication().invokeLater {
            xValue.computePresentation(object : XValueNode {
                override fun setPresentation(icon: javax.swing.Icon?, t: String?, value: String, children: Boolean) {
                    type = t; hasChildren = children
                    if (!isPlaceholder(value)) {
                        result.complete(VariableInfo(name, t, value.take(200), expandable = children))
                    }
                }
                override fun setPresentation(icon: javax.swing.Icon?, presentation: com.intellij.xdebugger.frame.presentation.XValuePresentation, children: Boolean) {
                    type = presentation.type; hasChildren = children
                    val rendered = renderPresentation(presentation)
                    if (!isPlaceholder(rendered)) {
                        result.complete(VariableInfo(name, presentation.type, rendered, expandable = children))
                    }
                }
                override fun setFullValueEvaluator(evaluator: XFullValueEvaluator) {
                    fullEvaluator = evaluator
                }
                override fun isObsolete(): Boolean = result.isDone
            }, XValuePlace.TREE)
        }

        try { return result.get(500, TimeUnit.MILLISECONDS) } catch (_: Exception) {}

        val evaluator = fullEvaluator
        if (evaluator != null) {
            val evalResult = CompletableFuture<String>()
            ApplicationManager.getApplication().invokeLater {
                evaluator.startEvaluation(object : XFullValueEvaluator.XFullValueEvaluationCallback {
                    override fun evaluated(fullValue: String) { evalResult.complete(fullValue) }
                    @Suppress("DEPRECATION")
                    override fun evaluated(fullValue: String, font: java.awt.Font?) { evalResult.complete(fullValue) }
                    override fun errorOccurred(errorMessage: String) { evalResult.complete(null) }
                    override fun isObsolete(): Boolean = evalResult.isDone
                })
            }
            try {
                val fullValue = evalResult.get(3, TimeUnit.SECONDS)
                if (fullValue != null) {
                    return VariableInfo(name, type, fullValue.take(200), expandable = hasChildren)
                }
            } catch (_: Exception) {}
        }

        return result.getNow(VariableInfo(name, type, "<evaluating>", expandable = hasChildren))
    }

    private fun isPlaceholder(value: String): Boolean {
        val lower = value.lowercase()
        return lower.contains("collecting data") || lower.contains("evaluating") || lower.contains("loading")
    }

    private fun renderPresentation(presentation: com.intellij.xdebugger.frame.presentation.XValuePresentation): String {
        val sb = StringBuilder()
        presentation.renderValue(object : com.intellij.xdebugger.frame.presentation.XValuePresentation.XValueTextRenderer {
            override fun renderValue(value: String) { sb.append(value) }
            override fun renderValue(value: String, key: com.intellij.openapi.editor.colors.TextAttributesKey) { sb.append(value) }
            override fun renderStringValue(value: String) { sb.append('"').append(value).append('"') }
            override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) {
                sb.append('"').append(value.take(maxLength)).append('"')
            }
            override fun renderNumericValue(value: String) { sb.append(value) }
            override fun renderKeywordValue(value: String) { sb.append(value) }
            override fun renderComment(comment: String) { sb.append(" // ").append(comment) }
            override fun renderSpecialSymbol(symbol: String) { sb.append(symbol) }
            override fun renderError(error: String) { sb.append("ERROR: ").append(error) }
        })
        val rendered = sb.toString()
        return if (rendered.isNotEmpty()) rendered.take(200) else "{${presentation.type ?: "object"}}"
    }

    private fun getStackFrame(session: XDebugSession, frameIndex: Int): XStackFrame? {
        if (frameIndex == 0) return session.currentStackFrame
        return null
    }

    // --- Private helpers ---

    private fun extractFrameLocation(frame: XStackFrame): Pair<String?, String?> {
        val pos = frame.sourcePosition ?: return null to null
        return runReadAction {
            val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(pos.file)
                ?: return@runReadAction null to null
            val element = psiFile.findElementAt(pos.offset) ?: return@runReadAction null to null
            val method = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                element, com.intellij.psi.PsiMethod::class.java,
            )
            val clazz = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                element, com.intellij.psi.PsiClass::class.java,
            )
            method?.name to clazz?.qualifiedName
        }
    }

    private fun step(action: (XDebugSession) -> Unit): SuspendInfo? = steppingLock.withLock {
        val session = activeSession ?: return null
        suspendGate.arm()
        ApplicationManager.getApplication().invokeAndWait { action(session) }
        suspendGate.awaitSuspend(STEP_TIMEOUT)
    }

    private fun startDebugSession(settings: com.intellij.execution.RunnerAndConfigurationSettings): SessionStatus {
        ApplicationManager.getApplication().invokeLater {
            ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
        }
        return waitForSessionStart()
    }

    private fun waitForSessionStart(): SessionStatus {
        val manager = XDebuggerManager.getInstance(project)
        repeat(20) {
            val session = manager.currentSession
            if (session != null) {
                activeSession = session
                attachSessionListener(session)
                return buildStatus(session)
            }
            Thread.sleep(250)
        }
        return errorStatus("Debug session did not start within 5 seconds.")
    }

    private fun attachSessionListener(session: XDebugSession) {
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() {
                val pos = session.currentPosition
                suspendGate.onSuspended(SuspendInfo(
                    file = pos?.file?.path,
                    line = pos?.line?.plus(1) ?: -1,
                    method = null, // Will extract method name in Task 8
                ))
            }

            override fun sessionStopped() {
                suspendGate.onSessionEnded(0)
                val cleanupResult = breakpointTracker.cleanup()
                performCleanup(cleanupResult)
                activeSession = null
                sessionType = null
            }
        })

        project.messageBus.connect(this).subscribe(
            XBreakpointListener.TOPIC,
            object : XBreakpointListener<XBreakpoint<*>> {
                override fun breakpointRemoved(breakpoint: XBreakpoint<*>) {
                    val pos = breakpoint.sourcePosition ?: return
                    val id = BreakpointId(pos.file.path, pos.line + 1)
                    breakpointTracker.untrackClaudeBreakpoint(id)
                    breakpointTracker.untrackDisabledUserBreakpoint(id)
                }

                override fun breakpointChanged(breakpoint: XBreakpoint<*>) {
                    if (!breakpoint.isEnabled) return
                    val pos = breakpoint.sourcePosition ?: return
                    val id = BreakpointId(pos.file.path, pos.line + 1)
                    breakpointTracker.untrackDisabledUserBreakpoint(id)
                }
            }
        )
    }

    private fun buildStatus(session: XDebugSession): SessionStatus {
        val pos = session.currentPosition
        return SessionStatus(
            active = true,
            suspended = session.isSuspended,
            file = pos?.file?.path,
            line = pos?.line?.plus(1) ?: -1,
            method = null, // Will extract method name in Task 8
            sessionType = sessionType,
        )
    }

    private fun performCleanup(result: CleanupResult) {
        for (id in result.claudeBreakpointsToRemove) {
            removeBpSilent(id.file, id.line)
        }
        for (id in result.userBreakpointsToReEnable) {
            enableBpSilent(id.file, id.line)
        }
        for (id in result.borrowedToReDisable) {
            disableBpSilent(id.file, id.line)
        }
    }

    private fun removeBp(file: String, line: Int, id: BreakpointId): String {
        return try {
            val bpManager = XDebuggerManager.getInstance(project).breakpointManager
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(resolveAbsolutePath(file)) ?: return "File not found: $file"
            val bpTypes = XDebuggerUtil.getInstance().getLineBreakpointTypes()
            val bpType = bpTypes.firstOrNull() ?: return "Line breakpoint type not available"
            val bp = bpManager.findBreakpointsAtLine(bpType, vf, line - 1).firstOrNull() as? XBreakpoint<*>
                ?: return "No breakpoint at $file:$line"
            ApplicationManager.getApplication().invokeAndWait {
                ApplicationManager.getApplication().runWriteAction {
                    bpManager.removeBreakpoint(bp)
                }
            }
            breakpointTracker.untrackClaudeBreakpoint(id)
            "Breakpoint removed at $file:$line"
        } catch (e: Exception) {
            "Failed to remove breakpoint: ${e.message}"
        }
    }

    private fun removeBpSilent(file: String, line: Int) {
        try { removeBp(file, line, BreakpointId(file, line)) } catch (_: Exception) {}
    }

    private fun enableBpSilent(file: String, line: Int) {
        try { setBpEnabled(file, line, enabled = true) } catch (_: Exception) {}
    }

    private fun disableBpSilent(file: String, line: Int) {
        try { setBpEnabled(file, line, enabled = false) } catch (_: Exception) {}
    }

    private fun setBpEnabled(file: String, line: Int, enabled: Boolean): String {
        return try {
            val bpManager = XDebuggerManager.getInstance(project).breakpointManager
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(resolveAbsolutePath(file)) ?: return "File not found: $file"
            val bpTypes = XDebuggerUtil.getInstance().getLineBreakpointTypes()
            val bpType = bpTypes.firstOrNull() ?: return "Line breakpoint type not available"
            val bp = bpManager.findBreakpointsAtLine(bpType, vf, line - 1).firstOrNull() as? XBreakpoint<*>
                ?: return "No breakpoint at $file:$line"

            val id = BreakpointId(file, line)
            val isClaudeOwned = breakpointTracker.isClaudeOwned(id)

            if (!enabled && !isClaudeOwned) {
                breakpointTracker.trackDisabledUserBreakpoint(id)
            }
            if (enabled) {
                breakpointTracker.untrackDisabledUserBreakpoint(id)
            }

            bp.isEnabled = enabled
            val action = if (enabled) "enabled" else "disabled"
            "Breakpoint $action at $file:$line"
        } catch (e: Exception) {
            "Failed to ${if (enabled) "enable" else "disable"} breakpoint: ${e.message}"
        }
    }

    private fun resolveAbsolutePath(file: String): String {
        return if (file.startsWith("/")) file
        else "${project.basePath}/$file"
    }

    private fun errorStatus(message: String): SessionStatus {
        log.warn("DebugBridge: $message")
        return SessionStatus(false, false, null, -1, message, null)
    }

    private fun createAdHocConfig(
        runManager: RunManager,
        type: AdHocType,
        target: String,
        args: String?,
        env: Map<String, String>,
    ): com.intellij.execution.RunnerAndConfigurationSettings? {
        return try {
            when (type) {
                AdHocType.JAVA_APP -> {
                    val configType = com.intellij.execution.configurations.ConfigurationTypeUtil
                        .findConfigurationType("Application") ?: return null
                    val factory = configType.configurationFactories.first()
                    val settings = runManager.createConfiguration("Claude Debug: $target", factory)
                    val config = settings.configuration as com.intellij.execution.application.ApplicationConfiguration
                    config.mainClassName = target
                    args?.let { config.programParameters = it }
                    env.forEach { (k, v) -> config.envs[k] = v }
                    findModuleForClass(target)?.let { config.setModule(it) }
                    settings
                }
                AdHocType.JAVA_TEST -> {
                    val configType = com.intellij.execution.configurations.ConfigurationTypeUtil
                        .findConfigurationType("JUnit") ?: return null
                    val factory = configType.configurationFactories.first()
                    val settings = runManager.createConfiguration("Claude Debug Test: $target", factory)
                    val config = settings.configuration
                    configureModuleBasedConfig(config, target)
                    settings
                }
                AdHocType.JS_DEBUG, AdHocType.NODE -> null
            }
        } catch (e: Exception) {
            log.warn("Failed to create ad-hoc config: ${e.message}")
            null
        }
    }

    private fun findModuleForClass(fqName: String): com.intellij.openapi.module.Module? {
        return runReadAction {
            val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
            val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(fqName, scope)
                ?: return@runReadAction null
            com.intellij.openapi.module.ModuleUtilCore.findModuleForPsiElement(psiClass)
        }
    }

    private fun configureModuleBasedConfig(
        config: com.intellij.execution.configurations.RunConfiguration,
        fqName: String,
    ) {
        val module = findModuleForClass(fqName) ?: return
        if (config is com.intellij.execution.configurations.ModuleBasedConfiguration<*, *>) {
            config.setModule(module)
        }
        try {
            val pd = config.javaClass.getMethod("getPersistentData").invoke(config) ?: return
            pd.javaClass.getField("MAIN_CLASS_NAME").set(pd, fqName)
            pd.javaClass.getField("TEST_OBJECT").set(pd, "class")
        } catch (_: Exception) {
            // JUnit plugin not available or API changed — module is still set
        }
    }

    private fun createAttachConfig(
        runManager: RunManager,
        host: String,
        port: Int,
        runtime: AttachRuntime,
    ): com.intellij.execution.RunnerAndConfigurationSettings? {
        return try {
            when (runtime) {
                AttachRuntime.JAVA -> {
                    val configType = com.intellij.execution.configurations.ConfigurationTypeUtil
                        .findConfigurationType("Remote") ?: return null
                    val factory = configType.configurationFactories.first()
                    val settings = runManager.createConfiguration("Claude Attach: $host:$port", factory)
                    val config = settings.configuration as com.intellij.execution.remote.RemoteConfiguration
                    config.HOST = host
                    config.PORT = port.toString()
                    settings
                }
                AttachRuntime.NODE -> null
            }
        } catch (e: Exception) {
            log.warn("Failed to create attach config: ${e.message}")
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun addLineBreakpointUnchecked(
        bpManager: com.intellij.xdebugger.breakpoints.XBreakpointManager,
        bpType: XLineBreakpointType<*>,
        url: String,
        line: Int,
        vf: com.intellij.openapi.vfs.VirtualFile,
    ): XBreakpoint<*> {
        val typed = bpType as XLineBreakpointType<com.intellij.xdebugger.breakpoints.XBreakpointProperties<Any>>
        val props = typed.createBreakpointProperties(vf, line)
        return bpManager.addLineBreakpoint(typed, url, line, props)
    }

    override fun dispose() {
        try {
            val cleanupResult = breakpointTracker.cleanup()
            performCleanup(cleanupResult)
        } catch (_: Exception) {}
        activeSession = null
        suspendGate.disarm()
    }
}
