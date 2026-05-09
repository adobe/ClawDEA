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

import com.adobe.clawdea.skills.SkillInfo
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

class SkillPickerDialog(
    project: Project,
    private val skills: List<SkillInfo>,
) : DialogWrapper(project, true) {

    private val listModel = DefaultListModel<SkillInfo>()
    private val skillList = JBList(listModel)
    private val searchField = JBTextField()

    var selectedSkill: SkillInfo? = null
        private set

    init {
        title = "Browse Skills"
        skills.forEach { listModel.addElement(it) }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.preferredSize = Dimension(600, 400)

        searchField.emptyText.text = "Filter skills..."
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filterSkills()
            override fun removeUpdate(e: DocumentEvent) = filterSkills()
            override fun changedUpdate(e: DocumentEvent) {}
        })
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> {
                        val next = (skillList.selectedIndex + 1).coerceAtMost(listModel.size() - 1)
                        skillList.selectedIndex = next
                        skillList.ensureIndexIsVisible(next)
                        e.consume()
                    }
                    KeyEvent.VK_UP -> {
                        val prev = (skillList.selectedIndex - 1).coerceAtLeast(0)
                        skillList.selectedIndex = prev
                        skillList.ensureIndexIsVisible(prev)
                        e.consume()
                    }
                    KeyEvent.VK_ENTER -> {
                        if (listModel.size() > 0) doOKAction()
                        e.consume()
                    }
                }
            }
        })
        panel.add(searchField, BorderLayout.NORTH)

        skillList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        skillList.cellRenderer = SkillCellRenderer()
        if (listModel.size() > 0) skillList.selectedIndex = 0
        skillList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) doOKAction()
            }
        })
        panel.add(JBScrollPane(skillList), BorderLayout.CENTER)

        val infoLabel = JLabel("${skills.size} skills available").apply {
            font = font.deriveFont(11f)
            border = BorderFactory.createEmptyBorder(4, 2, 0, 0)
        }
        panel.add(infoLabel, BorderLayout.SOUTH)

        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = searchField

    private fun filterSkills() {
        val query = searchField.text.lowercase().trim()
        listModel.clear()
        val filtered = if (query.isEmpty()) {
            skills
        } else {
            skills.filter {
                it.name.lowercase().contains(query) || it.description.lowercase().contains(query)
            }
        }
        filtered.forEach { listModel.addElement(it) }
        if (listModel.size() > 0) skillList.selectedIndex = 0
    }

    override fun doOKAction() {
        selectedSkill = skillList.selectedValue
        super.doOKAction()
    }

    private class SkillCellRenderer : ListCellRenderer<SkillInfo> {
        private val panel = JPanel(BorderLayout(8, 0))
        private val nameLabel = JLabel()
        private val descLabel = JLabel()
        private val pluginLabel = JLabel()

        init {
            panel.border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            nameLabel.font = nameLabel.font.deriveFont(13f)
            descLabel.font = descLabel.font.deriveFont(11f)
            descLabel.foreground = UIManager.getColor("Label.disabledForeground")
            pluginLabel.font = pluginLabel.font.deriveFont(10f)
            pluginLabel.foreground = UIManager.getColor("Label.disabledForeground")

            val textPanel = JPanel(BorderLayout(0, 2))
            textPanel.isOpaque = false
            val topRow = JPanel(BorderLayout())
            topRow.isOpaque = false
            topRow.add(nameLabel, BorderLayout.WEST)
            topRow.add(pluginLabel, BorderLayout.EAST)
            textPanel.add(topRow, BorderLayout.NORTH)
            textPanel.add(descLabel, BorderLayout.CENTER)
            panel.add(textPanel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out SkillInfo>,
            value: SkillInfo,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            nameLabel.text = "/${value.name}"
            descLabel.text = value.description.take(100)
            pluginLabel.text = value.pluginName

            if (isSelected) {
                panel.background = UIManager.getColor("List.selectionBackground")
                nameLabel.foreground = UIManager.getColor("List.selectionForeground")
                descLabel.foreground = UIManager.getColor("List.selectionForeground")
                pluginLabel.foreground = UIManager.getColor("List.selectionForeground")
            } else {
                panel.background = UIManager.getColor("List.background")
                nameLabel.foreground = UIManager.getColor("List.foreground")
                descLabel.foreground = UIManager.getColor("Label.disabledForeground")
                pluginLabel.foreground = UIManager.getColor("Label.disabledForeground")
            }
            panel.isOpaque = true
            return panel
        }
    }
}
