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

import java.text.NumberFormat
import java.util.Locale
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory

object ContextBudgetCalculator {

    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.length + 3) / 4
    }

    fun calculatePercentage(tokensUsed: Int, tokenBudget: Int): Int {
        if (tokenBudget <= 0) return 0
        return ((tokensUsed.toLong() * 100) / tokenBudget).toInt().coerceAtMost(100)
    }

    fun formatBudgetText(tokensUsed: Int, tokenBudget: Int): String {
        val nf = NumberFormat.getInstance(Locale.US)
        val pct = calculatePercentage(tokensUsed, tokenBudget)
        return "${nf.format(tokensUsed)} / ${nf.format(tokenBudget)} tokens ($pct%)"
    }
}

class ContextBudgetIndicator : JPanel(BorderLayout()) {

    private val progressBar = JProgressBar(0, 100).apply {
        preferredSize = Dimension(120, 12)
        isStringPainted = false
    }
    private val label = JLabel("").apply {
        font = font.deriveFont(10f)
    }

    init {
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
        add(progressBar, BorderLayout.WEST)
        add(label, BorderLayout.CENTER)
        isVisible = false
    }

    fun update(tokensUsed: Int, tokenBudget: Int) {
        val pct = ContextBudgetCalculator.calculatePercentage(tokensUsed, tokenBudget)
        progressBar.value = pct
        label.text = "  ${ContextBudgetCalculator.formatBudgetText(tokensUsed, tokenBudget)}"
        isVisible = true
    }

    fun clear() {
        progressBar.value = 0
        label.text = ""
        isVisible = false
    }
}
