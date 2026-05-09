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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class MentionPickerDialog(
    project: Project,
    private val provider: MentionCompletionProvider,
    initialPrefix: String = "",
) : DialogWrapper(project, true) {

    private val listModel = DefaultListModel<Any>()
    private val resultList = JBList<Any>(listModel)
    private val searchField = JBTextField()
    private val statusLabel = JLabel("Type to search files and symbols")
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("MentionSearch", 1)
    @Volatile private var searchGeneration = 0

    var selectedItem: MentionItem? = null
        private set

    init {
        title = "Find File or Symbol"
        init()
        if (initialPrefix.isNotEmpty()) {
            searchField.text = initialPrefix
        }
        runSearch(initialPrefix)
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.preferredSize = Dimension(650, 450)

        searchField.emptyText.text = "Search files, paths, classes, methods..."
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = scheduleSearch()
            override fun removeUpdate(e: DocumentEvent) = scheduleSearch()
            override fun changedUpdate(e: DocumentEvent) {}
        })
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> {
                        navigateList(1)
                        e.consume()
                    }
                    KeyEvent.VK_UP -> {
                        navigateList(-1)
                        e.consume()
                    }
                    KeyEvent.VK_ENTER -> {
                        if (selectCurrentItem()) {
                            doOKAction()
                        }
                        e.consume()
                    }
                }
            }
        })
        panel.add(searchField, BorderLayout.NORTH)

        resultList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        resultList.cellRenderer = MentionPickerCellRenderer()
        resultList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    if (selectCurrentItem()) doOKAction()
                }
            }
        })
        panel.add(JBScrollPane(resultList), BorderLayout.CENTER)

        statusLabel.font = statusLabel.font.deriveFont(11f)
        statusLabel.border = BorderFactory.createEmptyBorder(4, 2, 0, 0)
        panel.add(statusLabel, BorderLayout.SOUTH)

        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = searchField

    private var pendingSearch: java.util.concurrent.Future<*>? = null

    private fun scheduleSearch() {
        pendingSearch?.cancel(false)
        pendingSearch = executor.submit {
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                return@submit
            }
            SwingUtilities.invokeLater { runSearch(searchField.text.trim()) }
        }
    }

    private fun runSearch(query: String) {
        val generation = ++searchGeneration
        if (query.isBlank()) {
            val initial = provider.getInitialItems()
            if (generation != searchGeneration) return
            updateResults(initial, emptyList(), "")
            return
        }

        executor.submit {
            val fileResults = try {
                ApplicationManager.getApplication().runReadAction<List<MentionItem>> { provider.searchFiles(query) }
            } catch (_: Exception) { emptyList() }

            if (generation != searchGeneration) return@submit

            val symbolResults = try {
                ApplicationManager.getApplication().runReadAction<List<MentionItem>> { provider.searchSymbols(query) }
            } catch (_: Exception) { emptyList() }

            if (generation != searchGeneration) return@submit

            SwingUtilities.invokeLater {
                if (generation == searchGeneration) {
                    updateResults(fileResults, symbolResults, query)
                }
            }
        }
    }

    private fun updateResults(files: List<MentionItem>, symbols: List<MentionItem>, query: String) {
        listModel.clear()
        val seenPaths = mutableSetOf<String>()

        if (files.isNotEmpty()) {
            listModel.addElement(SectionHeader("Files"))
            for (item in files) {
                seenPaths.add(item.insertValue)
                listModel.addElement(item)
            }
        }

        val uniqueSymbols = symbols.filter { it.insertValue !in seenPaths || it.context != null }
        if (uniqueSymbols.isNotEmpty()) {
            listModel.addElement(SectionHeader("Symbols"))
            for (item in uniqueSymbols) {
                listModel.addElement(item)
            }
        }

        val total = files.size + uniqueSymbols.size
        statusLabel.text = if (query.isBlank()) {
            "${files.size} recent files"
        } else {
            "$total results for \"$query\""
        }

        selectFirstItem()
    }

    private fun selectFirstItem() {
        for (i in 0 until listModel.size()) {
            if (listModel.getElementAt(i) is MentionItem) {
                resultList.selectedIndex = i
                resultList.ensureIndexIsVisible(i)
                return
            }
        }
    }

    private fun navigateList(delta: Int) {
        val size = listModel.size()
        if (size == 0) return
        var idx = resultList.selectedIndex + delta
        while (idx in 0 until size && listModel.getElementAt(idx) is SectionHeader) {
            idx += delta
        }
        if (idx in 0 until size) {
            resultList.selectedIndex = idx
            resultList.ensureIndexIsVisible(idx)
        }
    }

    private fun selectCurrentItem(): Boolean {
        val value = resultList.selectedValue
        if (value is MentionItem) {
            selectedItem = value
            return true
        }
        return false
    }

    override fun doOKAction() {
        selectCurrentItem()
        super.doOKAction()
    }

    data class SectionHeader(val title: String)

    private class MentionPickerCellRenderer : ListCellRenderer<Any> {
        private val itemPanel = JPanel(BorderLayout(8, 0))
        private val nameLabel = JLabel()
        private val pathLabel = JLabel()
        private val contextLabel = JLabel()
        private val headerLabel = JLabel()

        init {
            itemPanel.border = BorderFactory.createEmptyBorder(5, 8, 5, 8)
            nameLabel.font = nameLabel.font.deriveFont(13f)
            pathLabel.font = pathLabel.font.deriveFont(11f)
            pathLabel.foreground = UIManager.getColor("Label.disabledForeground")
            contextLabel.font = contextLabel.font.deriveFont(11f)
            contextLabel.foreground = UIManager.getColor("Label.disabledForeground")
            headerLabel.font = headerLabel.font.deriveFont(java.awt.Font.BOLD, 11f)
            headerLabel.foreground = UIManager.getColor("Label.disabledForeground")
            headerLabel.border = BorderFactory.createEmptyBorder(8, 8, 4, 8)

            val textPanel = JPanel(BorderLayout(0, 1))
            textPanel.isOpaque = false
            val topRow = JPanel(BorderLayout())
            topRow.isOpaque = false
            topRow.add(nameLabel, BorderLayout.WEST)
            topRow.add(pathLabel, BorderLayout.CENTER)
            textPanel.add(topRow, BorderLayout.NORTH)
            textPanel.add(contextLabel, BorderLayout.CENTER)
            itemPanel.add(textPanel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out Any>,
            value: Any,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            if (value is SectionHeader) {
                headerLabel.text = value.title
                headerLabel.isOpaque = false
                return headerLabel
            }

            val item = value as MentionItem
            nameLabel.text = item.displayName
            pathLabel.text = "  ${item.insertValue}"
            contextLabel.text = item.context ?: ""
            contextLabel.isVisible = item.context != null

            if (isSelected) {
                itemPanel.background = UIManager.getColor("List.selectionBackground")
                nameLabel.foreground = UIManager.getColor("List.selectionForeground")
                pathLabel.foreground = UIManager.getColor("List.selectionForeground")
                contextLabel.foreground = UIManager.getColor("List.selectionForeground")
            } else {
                itemPanel.background = UIManager.getColor("List.background")
                nameLabel.foreground = UIManager.getColor("List.foreground")
                pathLabel.foreground = UIManager.getColor("Label.disabledForeground")
                contextLabel.foreground = UIManager.getColor("Label.disabledForeground")
            }
            itemPanel.isOpaque = true
            return itemPanel
        }
    }
}
