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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiModelTableModelTest {

    @Test
    fun `table has 9 columns`() {
        val model = OpenAiModelTableModel()
        assertEquals(9, model.columnCount)
    }

    @Test
    fun `column names match specification`() {
        val model = OpenAiModelTableModel()
        assertEquals("Enabled", model.getColumnName(0))
        assertEquals("ID", model.getColumnName(1))
        assertEquals("Display name", model.getColumnName(2))
        assertEquals("Capability", model.getColumnName(3))
        assertEquals("Input/M", model.getColumnName(4))
        assertEquals("Output/M", model.getColumnName(5))
        assertEquals("Cached/M", model.getColumnName(6))
        assertEquals("Reasoning/M", model.getColumnName(7))
        assertEquals("Context window", model.getColumnName(8))
    }

    @Test
    fun `context window column is Integer editable and parses string input`() {
        val model = OpenAiModelTableModel()
        model.rows.add(ModelEntry(id = "test", displayName = "Test", contextWindow = 128_000))

        assertEquals(Integer::class.java, model.getColumnClass(8))
        assertTrue(model.isCellEditable(0, 8))
        assertEquals(128_000, model.getValueAt(0, 8))

        // Int input
        model.setValueAt(32_768, 0, 8)
        assertEquals(32_768, model.rows[0].contextWindow)

        // String input (JBTable may hand back the edited text) is parsed
        model.setValueAt("65536", 0, 8)
        assertEquals(65_536, model.rows[0].contextWindow)

        // Non-numeric input falls back to 0 rather than throwing
        model.setValueAt("not-a-number", 0, 8)
        assertEquals(0, model.rows[0].contextWindow)
    }

    @Test
    fun `enabled column is boolean editable`() {
        val model = OpenAiModelTableModel()
        model.rows.add(ModelEntry(id = "test", displayName = "Test Model", enabled = true))

        assertEquals(Boolean::class.java, model.getColumnClass(0))
        assertTrue(model.isCellEditable(0, 0))
        assertEquals(true, model.getValueAt(0, 0))

        model.setValueAt(false, 0, 0)
        assertFalse(model.rows[0].enabled)
    }

    @Test
    fun `id and displayName columns are string editable`() {
        val model = OpenAiModelTableModel()
        model.rows.add(ModelEntry(id = "test-id", displayName = "Test Display"))

        assertEquals(String::class.java, model.getColumnClass(1))
        assertTrue(model.isCellEditable(0, 1))
        assertEquals("test-id", model.getValueAt(0, 1))

        model.setValueAt("new-id", 0, 1)
        assertEquals("new-id", model.rows[0].id)

        assertEquals("Test Display", model.getValueAt(0, 2))
        model.setValueAt("New Display", 0, 2)
        assertEquals("New Display", model.rows[0].displayName)
    }

    @Test
    fun `capability column is string editable`() {
        val model = OpenAiModelTableModel()
        model.rows.add(ModelEntry(id = "test", displayName = "Test", capability = "completion-only"))

        assertEquals(String::class.java, model.getColumnClass(3))
        assertTrue(model.isCellEditable(0, 3))
        assertEquals("completion-only", model.getValueAt(0, 3))

        model.setValueAt("agentic", 0, 3)
        assertEquals("agentic", model.rows[0].capability)
    }

    @Test
    fun `pricing columns are Double editable`() {
        val model = OpenAiModelTableModel()
        model.rows.add(ModelEntry(
            id = "test",
            displayName = "Test",
            inputPerM = 1.5,
            outputPerM = 2.5,
            cachedInputPerM = 0.5,
            reasoningPerM = 3.0,
        ))

        assertEquals(Double::class.java, model.getColumnClass(4))
        assertTrue(model.isCellEditable(0, 4))
        assertEquals(1.5, model.getValueAt(0, 4))

        model.setValueAt(10.0, 0, 4)
        assertEquals(10.0, model.rows[0].inputPerM, 0.001)

        model.setValueAt(20.0, 0, 5)
        assertEquals(20.0, model.rows[0].outputPerM, 0.001)

        model.setValueAt(5.0, 0, 6)
        assertEquals(5.0, model.rows[0].cachedInputPerM, 0.001)

        model.setValueAt(30.0, 0, 7)
        assertEquals(30.0, model.rows[0].reasoningPerM, 0.001)
    }

    @Test
    fun `addRow creates userAdded entry`() {
        val model = OpenAiModelTableModel()
        model.addRow()

        assertEquals(1, model.rowCount)
        assertTrue(model.rows[0].userAdded)
        assertEquals("", model.rows[0].id)
        assertEquals("", model.rows[0].displayName)
    }

    @Test
    fun `removeRow deletes entry`() {
        val model = OpenAiModelTableModel()
        model.rows.add(ModelEntry(id = "first", displayName = "First"))
        model.rows.add(ModelEntry(id = "second", displayName = "Second"))

        assertEquals(2, model.rowCount)
        model.removeRow(0)
        assertEquals(1, model.rowCount)
        assertEquals("second", model.rows[0].id)
    }

    @Test
    fun `replaceAll replaces all rows with copies`() {
        val model = OpenAiModelTableModel()
        model.rows.add(ModelEntry(id = "old", displayName = "Old"))

        val newEntries = listOf(
            ModelEntry(id = "new1", displayName = "New 1"),
            ModelEntry(id = "new2", displayName = "New 2"),
        )
        model.replaceAll(newEntries)

        assertEquals(2, model.rowCount)
        assertEquals("new1", model.rows[0].id)
        assertEquals("new2", model.rows[1].id)
    }
}
