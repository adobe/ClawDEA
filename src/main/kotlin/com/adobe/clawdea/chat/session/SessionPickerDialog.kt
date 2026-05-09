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
package com.adobe.clawdea.chat.session

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
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

class SessionPickerDialog(
    project: Project,
    private val sessions: List<SessionInfo>,
) : DialogWrapper(project, true) {

    private val listModel = DefaultListModel<SessionInfo>()
    private val sessionList = JBList(listModel)
    private val searchField = JBTextField()

    var selectedSession: SessionInfo? = null
        private set

    init {
        title = "Resume Session"
        sessions.forEach { listModel.addElement(it) }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.preferredSize = Dimension(600, 400)

        // Search field
        searchField.emptyText.text = "Filter sessions..."
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filterSessions()
            override fun removeUpdate(e: DocumentEvent) = filterSessions()
            override fun changedUpdate(e: DocumentEvent) {}
        })
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> {
                        val next = (sessionList.selectedIndex + 1).coerceAtMost(listModel.size() - 1)
                        sessionList.selectedIndex = next
                        sessionList.ensureIndexIsVisible(next)
                        e.consume()
                    }
                    KeyEvent.VK_UP -> {
                        val prev = (sessionList.selectedIndex - 1).coerceAtLeast(0)
                        sessionList.selectedIndex = prev
                        sessionList.ensureIndexIsVisible(prev)
                        e.consume()
                    }
                    KeyEvent.VK_ENTER -> {
                        if (listModel.size() > 0) {
                            doOKAction()
                        }
                        e.consume()
                    }
                }
            }
        })
        panel.add(searchField, BorderLayout.NORTH)

        // Session list
        sessionList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        sessionList.cellRenderer = SessionCellRenderer()
        if (listModel.size() > 0) {
            sessionList.selectedIndex = 0
        }
        sessionList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    doOKAction()
                }
            }
        })

        panel.add(JBScrollPane(sessionList), BorderLayout.CENTER)

        // Info label
        val infoLabel = JLabel("${sessions.size} sessions found").apply {
            font = font.deriveFont(11f)
            border = BorderFactory.createEmptyBorder(4, 2, 0, 0)
        }
        panel.add(infoLabel, BorderLayout.SOUTH)

        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = searchField

    private fun filterSessions() {
        val query = searchField.text.lowercase().trim()
        listModel.clear()
        val filtered = if (query.isEmpty()) {
            sessions
        } else {
            sessions.filter { it.firstMessage.lowercase().contains(query) }
        }
        filtered.forEach { listModel.addElement(it) }
        if (listModel.size() > 0) {
            sessionList.selectedIndex = 0
        }
    }

    override fun doOKAction() {
        selectedSession = sessionList.selectedValue
        super.doOKAction()
    }

    private class SessionCellRenderer : ListCellRenderer<SessionInfo> {
        private val panel = JPanel(BorderLayout(8, 0))
        private val messageLabel = JLabel()
        private val metaLabel = JLabel()

        init {
            panel.border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            messageLabel.font = messageLabel.font.deriveFont(13f)
            metaLabel.font = metaLabel.font.deriveFont(11f)
            metaLabel.foreground = UIManager.getColor("Label.disabledForeground")

            val textPanel = JPanel(BorderLayout(0, 2))
            textPanel.isOpaque = false
            textPanel.add(messageLabel, BorderLayout.CENTER)
            textPanel.add(metaLabel, BorderLayout.SOUTH)
            panel.add(textPanel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out SessionInfo>,
            value: SessionInfo,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            messageLabel.text = value.firstMessage.replace("\n", " ").take(100)
            val sizeKb = value.fileSize / 1024
            metaLabel.text = "${value.formattedTime()}  \u00b7  ${sizeKb}KB"

            if (isSelected) {
                panel.background = UIManager.getColor("List.selectionBackground")
                messageLabel.foreground = UIManager.getColor("List.selectionForeground")
                metaLabel.foreground = UIManager.getColor("List.selectionForeground")
            } else {
                panel.background = UIManager.getColor("List.background")
                messageLabel.foreground = UIManager.getColor("List.foreground")
                metaLabel.foreground = UIManager.getColor("Label.disabledForeground")
            }
            panel.isOpaque = true

            return panel
        }
    }
}
