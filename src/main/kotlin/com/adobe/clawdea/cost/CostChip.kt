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
package com.adobe.clawdea.cost

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale

/** Toolbar chip showing running spend / subscription window. Click to set a daily budget. */
class CostChip(
    private val project: Project,
    private val chatId: String,
    parentDisposable: Disposable,
) : JBLabel() {

    init {
        border = JBUI.Borders.empty(2, 8)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = CostControlPanel(project, chatId).showUnder(this@CostChip)
        })
        project.messageBus.connect(parentDisposable).subscribe(
            CostSnapshotListener.TOPIC,
            object : CostSnapshotListener {
                override fun onCostChanged() {
                    // Per-chat snapshot: re-query this chip's own chat total.
                    val snapshot = CostTracker.getInstance(project).snapshot(chatId)
                    ApplicationManager.getApplication().invokeLater({ render(snapshot) }, ModalityState.any())
                }
            },
        )
        render(CostTracker.getInstance(project).snapshot(chatId))
    }

    private fun render(s: CostSnapshot) {
        text = formatText(s)
        foreground = bandColor(s.band)
        toolTipText = buildTooltip(s)
    }


    private fun buildTooltip(s: CostSnapshot): String {
        val sb = StringBuilder("<html>")
        s.usage.spend?.let { sp ->
            sb.append("Subscription: ${sp.pct}% of limit used<br>")
        }
        s.usage.windows.forEach { w ->
            sb.append("${w.label}: ${w.pct}%<br>")
        }
        if (s.dailyBudgetUsd > 0) {
            sb.append("Daily budget: \$${String.format(Locale.US, "%.2f", s.dailyBudgetUsd)}<br>")
        }
        if (s.perModelUsd.isNotEmpty()) {
            sb.append("<b>By model:</b><br>")
            s.perModelUsd.entries.sortedByDescending { it.value }.forEach { (m, v) ->
                sb.append("&nbsp;$m: \$${String.format(Locale.US, "%.4f", v)}<br>")
            }
        }
        sb.append("Click to set a daily budget.</html>")
        return sb.toString()
    }

    companion object {
        /** Pure: chip label text. Provider-aware. Locale-independent number formatting. */
        fun formatText(s: CostSnapshot): String {
            val chat = String.format(Locale.US, "%.2f", s.sessionUsd)
            return when {
                // Subscription with a live spend gauge → show its utilization %.
                s.providerId == "subscription" && s.usage.spend != null ->
                    "usage ${s.usage.spend.pct}% · ≈\$$chat chat"
                // Subscription with only window data → worst window %.
                s.providerId == "subscription" && s.usage.windows.isNotEmpty() ->
                    "usage ${s.usage.windows.maxOf { it.pct }}% · ≈\$$chat chat"
                s.providerId == "subscription" ->
                    "≈\$$chat chat"
                else ->
                    "\$${String.format(Locale.US, "%.2f", s.dailyUsd)} today · \$$chat chat"
            }
        }

        private fun bandColor(band: CostBand): JBColor = when (band) {
            CostBand.RED -> JBColor.RED
            CostBand.AMBER -> JBColor(0xE0A92B, 0xE0A92B)
            CostBand.GREEN -> JBColor(0x4CAF50, 0x4CAF50)
            CostBand.NEUTRAL -> JBColor.GRAY
        }
    }
}
