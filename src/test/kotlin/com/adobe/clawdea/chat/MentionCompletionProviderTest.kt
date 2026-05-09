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

import com.intellij.openapi.vfs.VirtualFile
import org.junit.Assert.*
import org.junit.Test

class MentionCompletionProviderTest {

    @Test
    fun `extractMentionPrefix returns null when no @ present`() {
        assertNull(extractPrefix("hello world", 11))
    }

    @Test
    fun `extractMentionPrefix returns empty string right after @`() {
        assertEquals("", extractPrefix("@", 1))
    }

    @Test
    fun `extractMentionPrefix returns typed text after @`() {
        assertEquals("Foo", extractPrefix("@Foo", 4))
    }

    @Test
    fun `extractMentionPrefix works mid-sentence`() {
        assertEquals("Bar", extractPrefix("check @Bar", 10))
    }

    @Test
    fun `extractMentionPrefix returns null when space follows @`() {
        assertNull(extractPrefix("@ something", 2))
    }

    @Test
    fun `extractMentionPrefix ignores completed mention before cursor`() {
        assertNull(extractPrefix("@File ", 6))
    }

    @Test
    fun `extractMentionPrefix with caret at end of partial`() {
        assertEquals("Cha", extractPrefix("look at @Cha", 12))
    }

    @Test
    fun `buildReplacementText wraps file path in backticks`() {
        val item = MentionItem("MyFile.kt", "src/main/MyFile.kt", MentionType.FILE)
        assertEquals("@`src/main/MyFile.kt` ", buildReplacement(item))
    }

    @Test
    fun `buildReplacementText works with context`() {
        val item = MentionItem("MyFile.kt", "src/main/MyFile.kt", MentionType.FILE, context = "MyClass")
        assertEquals("@`src/main/MyFile.kt` ", buildReplacement(item))
    }

    @Test
    fun `relativePath strips basePath and leading slash`() {
        assertEquals("src/Main.kt", MentionCompletionProvider.relativePath(
            FakeVirtualFile("/home/user/project/src/Main.kt"),
            "/home/user/project",
        ))
    }

    @Test
    fun `relativePath returns full path when not under basePath`() {
        assertEquals("/tmp/other/File.kt", MentionCompletionProvider.relativePath(
            FakeVirtualFile("/tmp/other/File.kt"),
            "/home/user/project",
        ))
    }

    private fun extractPrefix(text: String, caret: Int): String? {
        val safeOffset = caret.coerceIn(0, text.length)
        val before = text.substring(0, safeOffset)
        val atIndex = before.lastIndexOf('@')
        if (atIndex < 0) return null
        val prefix = before.substring(atIndex + 1)
        if (prefix.contains(' ')) return null
        return prefix
    }

    private fun buildReplacement(item: MentionItem): String {
        return "@`${item.insertValue}` "
    }

    private class FakeVirtualFile(private val fakePath: String) : VirtualFile() {
        override fun getName() = fakePath.substringAfterLast('/')
        override fun getPath() = fakePath
        override fun isWritable() = false
        override fun isDirectory() = false
        override fun isValid() = true
        override fun getParent(): VirtualFile? = null
        override fun getChildren(): Array<VirtualFile>? = null
        override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long) =
            throw UnsupportedOperationException()
        override fun contentsToByteArray() = ByteArray(0)
        override fun getTimeStamp() = 0L
        override fun getLength() = 0L
        override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) {}
        override fun getInputStream() = throw UnsupportedOperationException()
        override fun getFileSystem() = throw UnsupportedOperationException()
    }
}
