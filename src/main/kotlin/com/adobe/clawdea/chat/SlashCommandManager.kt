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

import com.adobe.clawdea.commands.*
import java.awt.*
import java.awt.event.AWTEventListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class SlashCommandManager(
    private val inputHost: InputHost,
    private val commandRegistry: CommandRegistry,
) {
    private val slashProvider = SlashCompletionProvider()

    private sealed class SlashPopupEntry {
        data class Header(val label: String) : SlashPopupEntry()
        data class Item(val command: CommandInfo) : SlashPopupEntry()
    }

    private var slashPopup: JWindow? = null
    private var slashPopupList: JList<SlashPopupEntry>? = null
    private var slashClickOutsideListener: AWTEventListener? = null
    private var slashPopupOpen = false
    private val slashFilterListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = filterOpenSlashPopup()
        override fun removeUpdate(e: DocumentEvent) = filterOpenSlashPopup()
        override fun changedUpdate(e: DocumentEvent) {}
    }

    val isPopupOpen: Boolean get() = slashPopupOpen

    /**
     * Called when TAB is pressed and the popup is not yet open.
     * Returns true if a slash prefix was found and the popup was opened.
     */
    fun tryOpenFromTab(): Boolean {
        val text = inputHost.inputArea.text
        val prefix = slashProvider.extractSlashPrefix(text, inputHost.inputArea.caretPosition)
        if (prefix != null) {
            val allCommands = commandRegistry.allCommands()
            val filtered = slashProvider.filterCommands(allCommands, prefix)
            if (filtered.isNotEmpty()) {
                showSlashPopup(filtered)
                return true
            }
        }
        return false
    }

    /**
     * Called when Enter or TAB is pressed while the popup is open.
     * Returns true if a selection was accepted.
     */
    fun acceptSelection(): Boolean {
        val entry = slashPopupList?.selectedValue
        if (entry is SlashPopupEntry.Item) {
            insertSlashCommand(entry.command.name)
            return true
        }
        hideSlashPopup()
        return false
    }

    fun navigate(delta: Int) {
        val list = slashPopupList ?: return
        val model = list.model
        val size = model.size
        if (size == 0) return
        var index = if (list.selectedIndex == -1) {
            if (delta > 0) 0 else size - 1
        } else {
            list.selectedIndex + delta
        }
        // Skip headers
        while (index in 0 until size && model.getElementAt(index) is SlashPopupEntry.Header) {
            index += delta
        }
        if (index in 0 until size) {
            list.selectedIndex = index
            list.ensureIndexIsVisible(index)
        }
    }

    fun showSlashPopup(items: List<CommandInfo>) {
        if (slashPopupOpen && slashPopup != null) {
            // Already open -- just rebuild items in-place
            rebuildSlashPopupItems(items)
            return
        }
        hideSlashPopup()

        val model = DefaultListModel<SlashPopupEntry>()
        val list = JList(model).apply {
            cellRenderer = ListCellRenderer<SlashPopupEntry> { jList, value, _, isSelected, _ ->
                JLabel().apply {
                    isOpaque = true
                    border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
                    when (value) {
                        is SlashPopupEntry.Header -> {
                            text = value.label
                            font = font.deriveFont(Font.BOLD, 10f)
                            foreground = UIManager.getColor("Label.disabledForeground")
                            background = jList.background
                        }
                        is SlashPopupEntry.Item -> {
                            text = "${value.command.name}  —  ${value.command.description}"
                            font = font.deriveFont(12f)
                            if (isSelected) {
                                background = jList.selectionBackground
                                foreground = jList.selectionForeground
                            } else {
                                background = jList.background
                                foreground = jList.foreground
                            }
                        }
                    }
                }
            }
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val idx = locationToIndex(e.point)
                    if (idx >= 0) {
                        val entry = this@apply.model.getElementAt(idx)
                        if (entry is SlashPopupEntry.Item) {
                            insertSlashCommand(entry.command.name)
                        }
                    }
                }
            })
        }
        slashPopupList = list

        val scrollPane = JScrollPane(list).apply {
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            border = BorderFactory.createLineBorder(
                UIManager.getColor("Separator.foreground") ?: Color.GRAY,
            )
        }

        val window = JWindow(SwingUtilities.getWindowAncestor(inputHost.inputArea))
        window.focusableWindowState = false
        window.contentPane.add(scrollPane)
        slashPopup = window

        // Build initial items
        rebuildSlashPopupItems(items)

        // Constrain size: width of input area, max 300px tall
        val popupWidth = inputHost.inputArea.width.coerceAtLeast(300)
        val popupHeight = list.preferredSize.height.coerceAtMost(300).coerceAtLeast(40)
        window.setSize(popupWidth, popupHeight)

        // Show above the input area
        val inputScreenLoc = inputHost.inputArea.locationOnScreen
        window.setLocation(inputScreenLoc.x, inputScreenLoc.y - popupHeight)
        window.isVisible = true

        // Click-outside dismissal
        val listener = AWTEventListener { event ->
            if (event is MouseEvent && event.id == MouseEvent.MOUSE_PRESSED) {
                val w = slashPopup ?: return@AWTEventListener
                if (w.isVisible) {
                    val screenBounds = Rectangle(w.locationOnScreen, w.size)
                    if (!screenBounds.contains(event.locationOnScreen)) {
                        SwingUtilities.invokeLater { hideSlashPopup() }
                    }
                }
            }
        }
        slashClickOutsideListener = listener
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)

        // Activate live-filtering
        slashPopupOpen = true
        inputHost.inputArea.document.addDocumentListener(slashFilterListener)
    }

    fun hideSlashPopup() {
        val wasOpen = slashPopupOpen
        slashPopupOpen = false
        if (wasOpen) {
            inputHost.inputArea.document.removeDocumentListener(slashFilterListener)
        }
        slashClickOutsideListener?.let {
            Toolkit.getDefaultToolkit().removeAWTEventListener(it)
        }
        slashClickOutsideListener = null
        slashPopupList = null
        val window = slashPopup
        slashPopup = null
        window?.isVisible = false
        window?.dispose()
    }

    fun insertSlashCommand(commandName: String) {
        val caretPos = inputHost.inputArea.caretPosition
        val text = inputHost.inputArea.text
        val suffix = text.substring(caretPos)
        inputHost.inputArea.text = "$commandName $suffix"
        inputHost.inputArea.caretPosition = commandName.length + 1
        hideSlashPopup()
    }

    private fun filterOpenSlashPopup() {
        if (!slashPopupOpen) return
        val text = inputHost.inputArea.text
        // Use text.length instead of caretPosition: the DocumentListener fires
        // during the model update before the caret advances, so caretPosition
        // still reflects the old position and misses the newly typed character.
        val prefix = slashProvider.extractSlashPrefix(text, text.length)

        if (prefix == null) {
            // User typed a space or deleted the slash -- close popup
            hideSlashPopup()
            return
        }

        val allCommands = commandRegistry.allCommands()
        val filtered = slashProvider.filterCommands(allCommands, prefix)
        if (filtered.isEmpty()) {
            hideSlashPopup()
            return
        }
        rebuildSlashPopupItems(filtered)
    }

    private fun rebuildSlashPopupItems(items: List<CommandInfo>) {
        val list = slashPopupList ?: return
        val model = list.model as DefaultListModel<SlashPopupEntry>
        model.removeAllElements()
        val grouped = slashProvider.groupByCategory(items)
        val labelFor = mapOf(
            CommandCategory.SKILL to "Skills",
            CommandCategory.LOCAL to "Commands",
            CommandCategory.BRIDGE to "Commands",
            CommandCategory.DIALOG to "Commands",
            CommandCategory.INDEX to "Index Queries",
        )
        val sectionOrder = listOf("Skills", "Commands", "Index Queries")
        val seenLabels = mutableSetOf<String>()
        for (label in sectionOrder) {
            if (label in seenLabels) continue
            val sectionItems = grouped.entries
                .filter { (cat, _) -> labelFor[cat] == label }
                .flatMap { it.value }
            if (sectionItems.isEmpty()) continue
            seenLabels.add(label)
            model.addElement(SlashPopupEntry.Header(label))
            for (info in sectionItems) {
                model.addElement(SlashPopupEntry.Item(info))
            }
        }
        // Preselect the first non-header entry so Enter/Tab can accept immediately.
        for (i in 0 until model.size) {
            if (model.getElementAt(i) is SlashPopupEntry.Item) {
                list.selectedIndex = i
                list.ensureIndexIsVisible(i)
                break
            }
        }
    }
}
