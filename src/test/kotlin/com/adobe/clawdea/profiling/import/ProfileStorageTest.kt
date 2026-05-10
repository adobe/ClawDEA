package com.adobe.clawdea.profiling.`import`

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ProfileStorageTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var storage: ProfileStorage

    @Before
    fun setUp() {
        storage = ProfileStorage(tempDir.root.toPath())
    }

    @Test
    fun `store copies file with sha1-based name`() {
        val source = tempDir.newFile("my-recording.jfr").toPath()
        Files.write(source, "test content".toByteArray())

        val stored = storage.store(source, note = "test import")
        assertTrue(Files.exists(stored.path))
        assertTrue(stored.path.fileName.toString().endsWith("-my-recording.jfr"))
        assertTrue(stored.path.fileName.toString().length > "my-recording.jfr".length)
    }

    @Test
    fun `store creates metadata sidecar`() {
        val source = tempDir.newFile("recording.jfr").toPath()
        Files.write(source, "content".toByteArray())

        val stored = storage.store(source, note = "from prod")
        val metaPath = stored.path.resolveSibling(stored.path.fileName.toString() + ".meta.json")
        assertTrue(Files.exists(metaPath))
        val metaContent = Files.readString(metaPath)
        assertTrue(metaContent.contains("from prod"))
        assertTrue(metaContent.contains("sourcePath"))
    }

    @Test
    fun `list returns all stored recordings`() {
        val f1 = tempDir.newFile("a.jfr").toPath()
        val f2 = tempDir.newFile("b.jfr").toPath()
        Files.write(f1, "aaa".toByteArray())
        Files.write(f2, "bbb".toByteArray())

        storage.store(f1)
        storage.store(f2)

        val entries = storage.list()
        assertEquals(2, entries.size)
    }

    @Test
    fun `deduplication returns same path for identical content`() {
        val f1 = tempDir.newFile("first.jfr").toPath()
        Files.write(f1, "identical content".toByteArray())

        val stored1 = storage.store(f1)
        val stored2 = storage.store(f1)
        assertEquals(stored1.path, stored2.path)
    }

    @Test
    fun `evict removes oldest when over maxCount`() {
        val evictStorage = ProfileStorage(tempDir.root.toPath(), maxCount = 2)
        val f1 = tempDir.newFile("old.jfr").toPath()
        val f2 = tempDir.newFile("mid.jfr").toPath()
        val f3 = tempDir.newFile("new.jfr").toPath()
        Files.write(f1, "old".toByteArray())
        Files.write(f2, "mid".toByteArray())
        Files.write(f3, "new".toByteArray())

        evictStorage.store(f1)
        Thread.sleep(10)
        evictStorage.store(f2)
        Thread.sleep(10)
        evictStorage.store(f3)

        val entries = evictStorage.list()
        assertEquals(2, entries.size)
    }
}
