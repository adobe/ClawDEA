package com.adobe.clawdea.profiling.ui

import com.intellij.execution.RunManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

class ProfilingRunAction : AnAction("Run with ClawDEA Profiler"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val runManager = RunManager.getInstance(project)
        val selected = runManager.selectedConfiguration ?: return
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val runManager = project?.let { RunManager.getInstance(it) }
        e.presentation.isEnabledAndVisible = runManager?.selectedConfiguration != null
    }
}
