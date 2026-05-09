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

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBList
import java.awt.Component
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

class MentionAutocompleteManager(
    private val inputHost: InputHost,
    private val project: Project,
) {
    private val provider = MentionCompletionProvider(project)
    private var popup: JBPopup? = null
    private var listModel = DefaultListModel<MentionItem>()
    private var jbList = JBList(listModel)

    val isPopupOpen: Boolean get() = popup?.isVisible == true

    fun checkForMention() {
        if (inputHost.showingPlaceholder) return
        val text = inputHost.inputArea.text
        val caretPos = inputHost.inputArea.caretPosition
        val prefix = provider.extractMentionPrefix(text, caretPos)

        if (prefix == null) {
            hidePopup()
            return
        }

        val items = if (prefix.isEmpty()) {
            provider.getInitialItems()
        } else {
            provider.searchFiles(prefix)
        }

        if (items.isNotEmpty()) {
            showPopup(items)
        } else {
            hidePopup()
        }
    }

    fun navigate(delta: Int) {
        val size = listModel.size()
        if (size == 0) return
        val cur = jbList.selectedIndex
        val next = (cur + delta).coerceIn(0, size - 1)
        jbList.selectedIndex = next
        jbList.ensureIndexIsVisible(next)
    }

    fun acceptSelection(): Boolean {
        val item = jbList.selectedValue ?: return false
        insertMention(item)
        return true
    }

    fun openPickerDialog(): Boolean {
        hidePopup()
        val text = inputHost.inputArea.text
        val caretPos = inputHost.inputArea.caretPosition
        val prefix = provider.extractMentionPrefix(text, caretPos) ?: ""

        val dialog = MentionPickerDialog(project, provider, prefix)
        if (dialog.showAndGet()) {
            val selected = dialog.selectedItem ?: return false
            val atIndex = text.lastIndexOf('@', caretPos - 1)
            if (atIndex < 0) return false
            val replacement = provider.buildReplacementText(selected)
            val newText = text.substring(0, atIndex) + replacement + text.substring(caretPos)
            inputHost.inputArea.text = newText
            inputHost.inputArea.caretPosition = atIndex + replacement.length
            return true
        }
        return false
    }

    fun hidePopup() {
        popup?.cancel()
        popup = null
    }

    private fun insertMention(item: MentionItem) {
        val text = inputHost.inputArea.text
        val caretPos = inputHost.inputArea.caretPosition
        val atIndex = text.lastIndexOf('@', caretPos - 1)
        if (atIndex < 0) return

        val replacement = provider.buildReplacementText(item)
        val newText = text.substring(0, atIndex) + replacement + text.substring(caretPos)
        inputHost.inputArea.text = newText
        inputHost.inputArea.caretPosition = atIndex + replacement.length
        hidePopup()
    }

    private fun showPopup(items: List<MentionItem>) {
        hidePopup()

        listModel = DefaultListModel<MentionItem>()
        items.forEach { listModel.addElement(it) }
        jbList = JBList(listModel)
        jbList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        jbList.cellRenderer = MentionCellRenderer()
        if (listModel.size() > 0) jbList.selectedIndex = 0

        jbList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER || e.keyCode == KeyEvent.VK_TAB) {
                    e.consume()
                    acceptSelection()
                } else if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    e.consume()
                    hidePopup()
                }
            }
        })

        val newPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(JScrollPane(jbList), jbList)
            .setRequestFocus(false)
            .setFocusable(false)
            .setMovable(false)
            .setResizable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelKeyEnabled(true)
            .createPopup()

        try {
            val inputArea = inputHost.inputArea
            val caretRect = inputArea.modelToView2D(inputArea.caretPosition)
            if (caretRect != null) {
                val screenPoint = inputArea.locationOnScreen
                val x = screenPoint.x + caretRect.x.toInt()
                val y = screenPoint.y + caretRect.y.toInt() - (items.size.coerceAtMost(10) * 24)
                newPopup.showInScreenCoordinates(inputArea, java.awt.Point(x, y))
            }
        } catch (_: Exception) {
            newPopup.showUnderneathOf(inputHost.inputArea)
        }

        popup = newPopup
    }

    private class MentionCellRenderer : ListCellRenderer<MentionItem> {
        private val panel = JPanel(java.awt.BorderLayout(6, 0))
        private val nameLabel = JLabel()
        private val pathLabel = JLabel()

        init {
            panel.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            nameLabel.font = nameLabel.font.deriveFont(12f)
            pathLabel.font = pathLabel.font.deriveFont(11f)
            pathLabel.foreground = UIManager.getColor("Label.disabledForeground")

            val textPanel = JPanel(java.awt.BorderLayout(0, 0))
            textPanel.isOpaque = false
            textPanel.add(nameLabel, java.awt.BorderLayout.WEST)
            textPanel.add(pathLabel, java.awt.BorderLayout.CENTER)
            panel.add(textPanel, java.awt.BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out MentionItem>,
            value: MentionItem,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            nameLabel.text = value.displayName
            pathLabel.text = "  ${value.insertValue}"

            if (isSelected) {
                panel.background = UIManager.getColor("List.selectionBackground")
                nameLabel.foreground = UIManager.getColor("List.selectionForeground")
                pathLabel.foreground = UIManager.getColor("List.selectionForeground")
            } else {
                panel.background = UIManager.getColor("List.background")
                nameLabel.foreground = UIManager.getColor("List.foreground")
                pathLabel.foreground = UIManager.getColor("Label.disabledForeground")
            }
            panel.isOpaque = true
            return panel
        }
    }
}
