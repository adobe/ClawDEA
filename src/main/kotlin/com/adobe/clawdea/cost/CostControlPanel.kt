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
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Cost Control popup shown under the cost chip. Card-based layout matching the design prototype:
 * one provider card per used provider (subscription gets a colored spend gauge), a chat-scoped
 * breakdown card (this chat / knowledge upkeep / by model), and a daily-budget footer card.
 */
class CostControlPanel(private val project: Project, private val chatId: String) {

    fun showUnder(anchor: Component) {
        val content = build()
        // Scrollable so a tall breakdown is never clipped; sized to show everything without
        // needing a manual resize, capped so it never exceeds the surrounding view.
        val scroll = JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        val pref = content.preferredSize
        val host = (com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("ClawDEA")?.component) ?: anchor
        val maxH = (host.height.takeIf { it > 0 } ?: 600) - JBUI.scale(40)
        scroll.preferredSize = Dimension(
            pref.width + JBUI.scale(20),
            minOf(pref.height + JBUI.scale(8), maxH.coerceAtLeast(JBUI.scale(240))),
        )
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scroll, content)
            .setRequestFocus(true)
            .setTitle("Cost Control")
            .setMovable(true)
            .setResizable(true)
            .createPopup()
        // Center over the ClawDEA tool window (fall back to the chip anchor) instead of
        // dropping below the chip, where it was opening clipped off the bottom of the view.
        if (host.isShowing) {
            popup.showInCenterOf(host)
        } else {
            popup.showUnderneathOf(anchor)
        }
    }

    private fun money4(v: Double) = "$" + String.format(Locale.US, "%.4f", v)
    private fun money2(v: Double) = "$" + String.format(Locale.US, "%.2f", v)

    private fun build(): JComponent {
        val tracker = CostTracker.getInstance(project)
        val s = tracker.snapshot(chatId)

        val root = JPanel()
        root.layout = BoxLayout(root, BoxLayout.Y_AXIS)
        root.border = JBUI.Borders.empty(10)
        root.background = BG

        // (1) One card per provider used.
        val blocks = tracker.providerBlocks()
        if (blocks.isEmpty()) {
            root.add(card("No spend tracked yet.") { it.add(mutedLabel("Run a turn to start tracking.")) })
        }
        for (b in blocks) {
            root.add(providerCard(tracker, b))
            root.add(gap())
        }

        // (2) Chat-scoped breakdown card (this chat only).
        root.add(card("This chat") { body ->
            body.add(kvRow("Cost", money2(s.sessionUsd), bold = true))
            body.add(sectionLabel("By model"))
            if (s.perModelUsd.isEmpty()) {
                body.add(mutedLabel("  (no per-model data yet)"))
            } else {
                s.perModelUsd.entries.sortedByDescending { it.value }.forEach { (m, v) ->
                    body.add(kvRow("  " + prettyModel(m), money4(v)))
                }
            }
        })
        root.add(gap())

        // (3) ClawDEA Estimated Savings / Cost — sign-driven section. Absorbs knowledge upkeep
        // as a cost line so users never double-subtract it from a separate figure.
        root.add(savingsCard(s))
        root.add(gap())

        // (4) Daily-budget footer card.
        root.add(budgetCard())
        return root
    }

    // ---- provider card -------------------------------------------------------------------------

    private fun providerCard(tracker: CostTracker, b: ProviderBlock): JComponent {
        val title = providerTitle(b.providerId)
        return card(title) { body ->
            if (b.providerId == "subscription") {
                val spend = b.usage.spend
                if (spend != null) {
                    body.add(kvRow("${money2(spend.used)} of ${money2(spend.limit)} ${spend.currency}", "${spend.pct}%", bold = true))
                    body.add(gauge(spend.pct))
                }
                b.usage.windows.forEach { w ->
                    body.add(kvRow(w.label, "${w.pct}%"))
                    body.add(gauge(w.pct))
                }
                if (spend == null && b.usage.windows.isEmpty()) {
                    body.add(mutedLabel("usage unavailable"))
                }
            } else {
                val t = b.total
                if (t != null) {
                    body.add(kvRow("This month", money2(t.monthToDate), bold = true))
                    body.add(kvRow("Since ${t.sinceDate.ifBlank { "—" }}", money2(t.allTime)))
                    val reset = JButton("Reset").apply {
                        isOpaque = false
                        addActionListener { tracker.resetProvider(b.providerId); isEnabled = false; text = "Reset ✓" }
                    }
                    body.add(JPanel(BorderLayout()).apply { isOpaque = false; add(reset, BorderLayout.EAST) })
                } else {
                    body.add(mutedLabel("no spend tracked yet"))
                }
            }
        }
    }

    private fun providerTitle(providerId: String): String = when (providerId) {
        "subscription" -> "Subscription"
        "bedrock" -> "Bedrock"
        "anthropic" -> "Anthropic API"
        "vertex" -> "Vertex AI"
        else -> providerId.replaceFirstChar { it.uppercase() }
    }

    // ---- savings card --------------------------------------------------------------------------

    private fun money2signed(v: Double): String {
        val sign = if (v >= 0) "+" else "−"
        return sign + "$" + String.format(Locale.US, "%.2f", kotlin.math.abs(v))
    }

    private fun rangeText(b: com.adobe.clawdea.cost.SavingsBand): String =
        "${money2signed(b.low)} … ${money2signed(b.high)}"

    private fun leverLabel(id: com.adobe.clawdea.cost.LeverId) = when (id) {
        com.adobe.clawdea.cost.LeverId.LIBRARIAN -> "Librarian routing"
        com.adobe.clawdea.cost.LeverId.INDEX_TOOLS -> "IDE index tools"
        com.adobe.clawdea.cost.LeverId.KNOWLEDGE_UPKEEP -> "Knowledge upkeep"
        com.adobe.clawdea.cost.LeverId.PRIMER_OVERHEAD -> "Primer overhead"
    }

    private fun savingsCard(costSnap: CostSnapshot): JComponent {
        val tracker = com.adobe.clawdea.cost.SavingsTracker.getInstance(project)
        val snap = tracker.snapshot(chatId)
        // Fold measured knowledge-upkeep dollars (from the existing CostSnapshot) into the
        // displayed cost breakdown so it lives inside this one section, never a separate card.
        val upkeep = costSnap.knowledgeUsd.values.sum()
        val title = if (snap.isNetSaving) "ClawDEA Estimated Savings" else "ClawDEA Estimated Cost"
        return card(title) { body ->
            if (snap.isCollecting) {
                body.add(mutedLabel("Collecting… run a few turns to see an estimate."))
                return@card
            }
            // Scope rows.
            body.add(kvRow("This chat", money2signed(snap.sessionBand.expected), bold = true))
            body.add(mutedLabel("  " + rangeText(snap.sessionBand)))
            body.add(kvRow("This month", money2signed(snap.cumulative.mtd.expected)))
            body.add(kvRow("All time", money2signed(snap.cumulative.allTime.expected)))
            body.add(mutedLabel("  since ${snap.cumulative.sinceDate.ifBlank { "—" }}"))

            body.add(sectionLabel("Saved (this chat)"))
            for (c in snap.components.filter { !it.measured }) {
                val pm = String.format(Locale.US, "%.2f", (c.band.high - c.band.low) / 2.0)
                body.add(kvRow("  " + leverLabel(c.leverId), money2signed(c.band.expected) + "  (±$pm)"))
            }
            body.add(sectionLabel("Cost (this chat)"))
            for (c in snap.components.filter { it.measured && it.leverId != com.adobe.clawdea.cost.LeverId.KNOWLEDGE_UPKEEP }) {
                body.add(kvRow("  " + leverLabel(c.leverId), money2signed(c.band.expected)))
            }
            // Knowledge upkeep (global, measured) shown explicitly as a single cost line.
            body.add(kvRow("  Knowledge upkeep", money2signed(-upkeep)))

            val conf = com.adobe.clawdea.cost.SavingsEstimator.confidence(snap.sessionBand)
            val confLabel = if (conf == com.adobe.clawdea.cost.Confidence.ESTIMATE) "estimate" else "rough estimate"
            body.add(kvRow("Net (expected)", money2signed(snap.sessionBand.expected) + " · $confLabel", bold = true))

            val reset = JButton("Reset all-time").apply {
                isOpaque = false
                addActionListener { tracker.resetCumulative(); isEnabled = false; text = "Reset ✓" }
            }
            body.add(JPanel(BorderLayout()).apply { isOpaque = false; add(reset, BorderLayout.EAST) })
            body.add(mutedLabel("Estimated vs standard Claude Code; directional, not exact."))
        }
    }

    // ---- budget card ---------------------------------------------------------------------------

    private fun budgetCard(): JComponent = card("Daily budget") { body ->
        val settings = ClawDEASettings.getInstance()
        val field = JTextField(if (settings.state.dailyBudgetUsd > 0) settings.state.dailyBudgetUsd.toString() else "", 7)
        val apply = JButton("Set").apply {
            addActionListener {
                field.text.trim().toDoubleOrNull()?.let {
                    settings.state.dailyBudgetUsd = it.coerceAtLeast(0.0)
                    // Republish so the current chat's chip (and every open chip) re-bands now.
                    CostTracker.getInstance(project).refresh()
                }
            }
        }
        val row = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel("$").apply { foreground = MUTED })
            add(field)
            add(JLabel(" ").apply { isOpaque = false })
            add(apply)
            // Keep the input/button at their natural size and anchored left, instead of
            // stretching across the (now wider) card.
            add(javax.swing.Box.createHorizontalGlue())
        }
        body.add(row)
        body.add(mutedLabel("0 = no budget. Chip turns amber at 75%, red at 90%."))
    }

    // ---- building blocks -----------------------------------------------------------------------

    /** A titled rounded card. [fill] populates the card body (BoxLayout Y). */
    private fun card(title: String, fill: (JPanel) -> Unit): JComponent {
        val outer = RoundedPanel()
        outer.layout = BoxLayout(outer, BoxLayout.Y_AXIS)
        outer.border = JBUI.Borders.empty(10, 12)
        outer.alignmentX = Component.LEFT_ALIGNMENT
        outer.add(JLabel(title).apply {
            font = JBFont.label().asBold()
            foreground = TITLE
            alignmentX = Component.LEFT_ALIGNMENT
        })
        outer.add(JPanel().apply { isOpaque = false; maximumSize = Dimension(Int.MAX_VALUE, 4) })
        val body = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        fill(body)
        outer.add(body)
        outer.maximumSize = Dimension(Int.MAX_VALUE, outer.preferredSize.height)
        return outer
    }

    /** A label : value row, value right-aligned. */
    private fun kvRow(label: String, value: String, bold: Boolean = false): JComponent =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(20))
            add(JLabel(label).apply { foreground = if (bold) TEXT else MUTED }, BorderLayout.WEST)
            add(JLabel(value).apply {
                foreground = TEXT
                if (bold) font = JBFont.label().asBold()
            }, BorderLayout.EAST)
        }

    private fun sectionLabel(text: String): JComponent = JLabel(text).apply {
        font = JBFont.small()
        foreground = MUTED
        border = JBUI.Borders.emptyTop(6)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun mutedLabel(text: String): JComponent = JLabel(text).apply {
        foreground = MUTED
        alignmentX = Component.LEFT_ALIGNMENT
    }

    /** A thin colored utilization bar (green/amber/red by threshold). */
    private fun gauge(pct: Int): JComponent = object : JComponent() {
        init {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(6))
            preferredSize = Dimension(JBUI.scale(200), JBUI.scale(6))
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width
            val h = height
            val arc = h
            g2.color = TRACK
            g2.fillRoundRect(0, 0, w, h, arc, arc)
            val filled = (w * pct.coerceIn(0, 100) / 100.0).toInt()
            g2.color = when {
                pct >= 90 -> RED
                pct >= 75 -> AMBER
                else -> GREEN
            }
            if (filled > 0) g2.fillRoundRect(0, 0, filled, h, arc, arc)
            g2.dispose()
        }
    }

    private fun gap(): JComponent = JPanel().apply {
        isOpaque = false
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(8))
    }

    /** "claude-opus-4-8" → "Opus 4.8"; falls back to the raw id. */
    private fun prettyModel(id: String): String {
        val core = id.substringAfterLast("claude-", id).substringAfterLast('.')
        val parts = core.split('-').filter { it.isNotBlank() }
        if (parts.isEmpty()) return id
        val name = parts.first().replaceFirstChar { it.uppercase() }
        val ver = parts.drop(1).joinToString(".")
        return if (ver.isBlank()) name else "$name $ver"
    }

    /** Rounded, slightly-raised card background that adapts to the IDE theme. */
    private class RoundedPanel : JPanel() {
        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = CARD
            val arc = JBUI.scale(10)
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = BORDER
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }
    }

    private companion object {
        val BG = JBColor.namedColor("Popup.background", JBColor(0xF7F8FA, 0x1E1F22))
        val CARD = JBColor.namedColor("Component.background", JBColor(0xFFFFFF, 0x26282C))
        val BORDER = JBColor.namedColor("Component.borderColor", JBColor(0xD5D8DD, 0x3A3D42))
        val TITLE = JBColor.namedColor("Label.foreground", JBColor(0x4A5DA8, 0x8AB4F8))
        val TEXT = JBColor.namedColor("Label.foreground", JBColor(0x2B2D31, 0xCDD0D6))
        val MUTED = JBColor.namedColor("Label.infoForeground", JBColor(0x7D8189, 0x7D8189))
        val TRACK = JBColor(0xE0E2E6, 0x34363B)
        val GREEN = JBColor(0x4CAF50, 0x4CAF50)
        val AMBER = JBColor(0xE0A92B, 0xE0A92B)
        val RED = JBColor(0xE5534B, 0xE5534B)
    }
}
