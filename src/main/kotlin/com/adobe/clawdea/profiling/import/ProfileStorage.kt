package com.adobe.clawdea.profiling.`import`

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

data class StoredRecording(
    val path: Path,
    val id: String,
    val timestamp: Instant,
)

data class RecordingMeta(
    val sourcePath: String,
    val importTimestamp: String,
    val note: String?,
    val sizeBytes: Long,
)

class ProfileStorage(
    private val baseDir: Path,
    private val maxCount: Int = 20,
    private val maxSizeBytes: Long = 5L * 1024 * 1024 * 1024,
) {
    private val gson = Gson()
    private val storageDir: Path = baseDir.resolve("recordings")

    init {
        Files.createDirectories(storageDir)
    }

    fun store(source: Path, note: String? = null): StoredRecording {
        val sha = sha1Prefix(source)
        val targetName = "$sha-${source.fileName}"
        val targetPath = storageDir.resolve(targetName)

        if (Files.exists(targetPath)) {
            return StoredRecording(targetPath, sha, Files.getLastModifiedTime(targetPath).toInstant())
        }

        Files.copy(source, targetPath)
        val now = Instant.now()

        val meta = RecordingMeta(
            sourcePath = source.toAbsolutePath().toString(),
            importTimestamp = now.toString(),
            note = note,
            sizeBytes = Files.size(targetPath),
        )
        val metaPath = storageDir.resolve("$targetName.meta.json")
        Files.writeString(metaPath, gson.toJson(meta))

        evict()

        return StoredRecording(targetPath, sha, now)
    }

    fun list(): List<StoredRecording> {
        if (!Files.exists(storageDir)) return emptyList()
        return Files.list(storageDir).use { stream ->
            stream
                .filter {
                    val name = it.fileName.toString()
                    name.endsWith(".jfr") || name.endsWith(".hprof")
                }
                .map { path ->
                    val sha = path.fileName.toString().substringBefore('-')
                    StoredRecording(path, sha, Files.getLastModifiedTime(path).toInstant())
                }
                .sorted(compareByDescending { it.timestamp })
                .toList()
        }
    }

    private fun evict() {
        val entries = list().sortedBy { it.timestamp }
        if (entries.size > maxCount) {
            entries.take(entries.size - maxCount).forEach { delete(it) }
        }
        // Size-based eviction
        val remaining = list().sortedBy { it.timestamp }
        var totalSize = remaining.sumOf { Files.size(it.path) }
        val sorted = remaining.toMutableList()
        while (totalSize > maxSizeBytes && sorted.isNotEmpty()) {
            val oldest = sorted.removeFirst()
            totalSize -= Files.size(oldest.path)
            delete(oldest)
        }
    }

    private fun delete(entry: StoredRecording) {
        Files.deleteIfExists(entry.path)
        Files.deleteIfExists(entry.path.resolveSibling("${entry.path.fileName}.meta.json"))
    }

    private fun sha1Prefix(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(1024 * 1024)
        Files.newInputStream(path).use { input ->
            val read = input.read(buffer)
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }
}
