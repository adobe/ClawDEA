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

import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JTextField

/** Cost Control popup under the cost chip: per-provider headers + chat-scoped body + budget footer. */
class CostControlPanel(private val project: Project, private val chatId: String) {

    fun showUnder(anchor: Component) {
        val content = build()
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, content)
            .setRequestFocus(true)
            .setTitle("Cost Control")
            .setMovable(true)
            .setResizable(true)
            .createPopup()
            .showUnderneathOf(anchor)
    }

    private fun money4(v: Double) = "$" + String.format(Locale.US, "%.4f", v)
    private fun money2(v: Double) = "$" + String.format(Locale.US, "%.2f", v)

    private fun build(): JComponent {
        val tracker = CostTracker.getInstance(project)
        val s = tracker.snapshot(chatId)
        val root = JPanel()
        root.layout = BoxLayout(root, BoxLayout.Y_AXIS)
        root.border = JBUI.Borders.empty(10)

        // (1) One header block per provider used.
        val blocks = tracker.providerBlocks()
        if (blocks.isEmpty()) root.add(JLabel("No spend tracked yet."))
        for (b in blocks) {
            val headerText = if (b.providerId == "subscription") {
                CostPanelFormat.subscriptionHeader(b.usage)
            } else {
                b.total?.let { CostPanelFormat.bedrockHeader(it) } ?: "no spend tracked yet"
            }
            root.add(JLabel("${b.providerId}: $headerText"))
            if (b.providerId != "subscription" && b.total != null) {
                root.add(JButton("Reset ${b.providerId} totals").apply {
                    addActionListener { tracker.resetProvider(b.providerId); isEnabled = false }
                })
            }
        }

        // (2) Shared chat-scoped body. Sections always render (with $0.00) so the
        // panel is a complete at-a-glance breakdown, not a sometimes-empty list.
        root.add(JSeparator())
        root.add(JLabel("This chat: " + money2(s.sessionUsd)))

        root.add(JLabel("Knowledge upkeep:"))
        for (bucket in KnowledgeBucket.entries) {
            root.add(JLabel("  " + bucketLabel(bucket) + ": " + money2(s.knowledgeUsd[bucket] ?: 0.0)))
        }

        root.add(JLabel("By model:"))
        if (s.perModelUsd.isEmpty()) {
            root.add(JLabel("  (no per-model data yet)"))
        } else {
            s.perModelUsd.entries.sortedByDescending { it.value }.forEach { (m, v) ->
                root.add(JLabel("  $m: " + money4(v)))
            }
        }

        // (3) Daily-budget footer (retained from the old dialog).
        root.add(JSeparator())
        val settings = ClawDEASettings.getInstance()
        val field = JTextField(if (settings.state.dailyBudgetUsd > 0) settings.state.dailyBudgetUsd.toString() else "", 8)
        val apply = JButton("Set daily budget").apply {
            addActionListener {
                field.text.trim().toDoubleOrNull()?.let { settings.state.dailyBudgetUsd = it.coerceAtLeast(0.0) }
            }
        }
        root.add(JPanel().apply { add(JLabel("Daily budget $")); add(field); add(apply) })
        return root
    }

    private fun bucketLabel(b: KnowledgeBucket) = when (b) {
        KnowledgeBucket.WIKI_CREATE -> "Wiki · create"
        KnowledgeBucket.WIKI_UPDATE -> "Wiki · update"
        KnowledgeBucket.WORKSPACE_CREATE -> "Workspace · create"
        KnowledgeBucket.WORKSPACE_UPDATE -> "Workspace · update"
    }
}
