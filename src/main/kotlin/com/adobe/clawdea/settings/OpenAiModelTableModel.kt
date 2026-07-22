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

import com.adobe.clawdea.gateway.ModelEntry
import javax.swing.table.AbstractTableModel

class OpenAiModelTableModel(
    val rows: MutableList<ModelEntry> = mutableListOf(),
) : AbstractTableModel() {

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = 9

    override fun getColumnName(column: Int): String = when (column) {
        0 -> "Enabled"
        1 -> "ID"
        2 -> "Display name"
        3 -> "Capability"
        4 -> "Input/M"
        5 -> "Output/M"
        6 -> "Cached/M"
        7 -> "Reasoning/M"
        8 -> "Context window"
        else -> ""
    }

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        0 -> Boolean::class.java
        1, 2, 3 -> String::class.java
        4, 5, 6, 7 -> Double::class.java
        8 -> Integer::class.java
        else -> Any::class.java
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (columnIndex) {
        0 -> rows[rowIndex].enabled
        1 -> rows[rowIndex].id
        2 -> rows[rowIndex].displayName
        3 -> rows[rowIndex].capability
        4 -> rows[rowIndex].inputPerM
        5 -> rows[rowIndex].outputPerM
        6 -> rows[rowIndex].cachedInputPerM
        7 -> rows[rowIndex].reasoningPerM
        8 -> rows[rowIndex].contextWindow
        else -> ""
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        val entry = rows[rowIndex]
        when (columnIndex) {
            0 -> entry.enabled = aValue as? Boolean ?: false
            1 -> entry.id = aValue?.toString() ?: ""
            2 -> entry.displayName = aValue?.toString() ?: ""
            3 -> entry.capability = aValue?.toString() ?: ""
            4 -> entry.inputPerM = (aValue as? Double) ?: 0.0
            5 -> entry.outputPerM = (aValue as? Double) ?: 0.0
            6 -> entry.cachedInputPerM = (aValue as? Double) ?: 0.0
            7 -> entry.reasoningPerM = (aValue as? Double) ?: 0.0
            8 -> entry.contextWindow = when (aValue) {
                is Int -> aValue
                is Number -> aValue.toInt()
                else -> aValue?.toString()?.trim()?.toIntOrNull() ?: 0
            }
        }
        entry.userAdded = true
        fireTableRowsUpdated(rowIndex, rowIndex)
    }

    fun addRow() {
        rows.add(ModelEntry(id = "", displayName = "", userAdded = true))
        fireTableRowsInserted(rows.size - 1, rows.size - 1)
    }

    fun removeRow(index: Int) {
        rows.removeAt(index)
        fireTableRowsDeleted(index, index)
    }

    fun replaceAll(newRows: List<ModelEntry>) {
        rows.clear()
        rows.addAll(newRows.map { it.copy() })
        fireTableDataChanged()
    }
}
