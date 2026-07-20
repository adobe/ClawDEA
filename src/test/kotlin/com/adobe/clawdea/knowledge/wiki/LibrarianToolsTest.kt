package com.adobe.clawdea.knowledge.wiki

import com.adobe.clawdea.provider.openai.agent.OpenAiFunctionDefinition
import com.adobe.clawdea.provider.openai.agent.OpenAiToolDefinition
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarianToolsTest {
    private fun tool(name: String) =
        OpenAiToolDefinition(function = OpenAiFunctionDefinition(name, "d", JsonObject()))

    @Test fun keeps_readonly_wiki_and_index_tools() {
        val kept = filterLibrarianTools(listOf(
            tool("read_wiki_page"), tool("find_symbol"), tool("search_text"),
            tool("resolve_symbol"), tool("get_diagnostics"), tool("record_wiki_suggestion"),
        )).map { it.function.name }.toSet()
        assertTrue(kept.contains("read_wiki_page"))
        assertTrue(kept.contains("find_symbol"))
        assertTrue(kept.contains("record_wiki_suggestion"))
    }

    @Test fun drops_edit_write_and_shell_tools() {
        val kept = filterLibrarianTools(listOf(
            tool("read_wiki_page"),
            tool("propose_write"), tool("propose_edit"), tool("propose_multi_edit"),
            tool("Bash"), tool("apply_patch"), tool("Write"), tool("Edit"),
        )).map { it.function.name }
        assertEquals(listOf("read_wiki_page"), kept)
        assertFalse(kept.contains("propose_write"))
        assertFalse(kept.contains("Bash"))
    }
}
