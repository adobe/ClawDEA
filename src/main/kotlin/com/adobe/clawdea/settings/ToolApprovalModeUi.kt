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
package com.adobe.clawdea.settings

import com.intellij.icons.AllIcons
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList

object ToolApprovalModeUi {
    enum class Severity {
        NONE,
        WARNING,
        DANGER,
    }

    data class Mode(
        val key: String,
        val label: String,
        val severity: Severity,
        val icon: Icon?,
    )

    private val modes = listOf(
        Mode("confirm-all", "Ask unlisted", Severity.NONE, AllIcons.Actions.Help),
        Mode("allow-safe", "Allow safe", Severity.WARNING, AllIcons.General.Warning),
        Mode("allow-all", "Allow all", Severity.DANGER, AllIcons.General.Error),
    )
    private val modesByLabel = modes.associateBy { it.label }

    const val TOOLTIP_TEXT: String =
        "<html>Tool approval policy:" +
            "<br>Ask unlisted: honors previously allowed or denied commands, then prompts for everything else." +
            "<br>Allow safe: can auto-run tools classified as safe." +
            "<br>Allow all: dangerous; bypasses all tool prompts.</html>"

    fun comboBoxModel(): DefaultComboBoxModel<String> =
        DefaultComboBoxModel(modes.map { it.label }.toTypedArray())

    fun modeFor(label: String?): Mode =
        modesByLabel[label] ?: Mode(label.orEmpty(), label.orEmpty(), Severity.NONE, null)

    fun indexForKey(key: String): Int =
        modes.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: 0

    fun keyForIndex(index: Int): String =
        modes[index.coerceIn(0, modes.lastIndex)].key

    fun isAllowAll(key: String): Boolean =
        key == "allow-all"

    fun labelForKey(key: String): String =
        modes.firstOrNull { it.key == key }?.label ?: modes.first().label

    fun requiresCliRestart(oldKey: String, newKey: String): Boolean =
        oldKey != newKey

    fun installRenderer(combo: JComboBox<String>) {
        combo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                val mode = modeFor(value?.toString())
                component.text = mode.label
                component.icon = mode.icon
                component.iconTextGap = 6
                return component
            }
        }
    }
}
