package com.adobe.clawdea.profiling.extensions

import com.intellij.openapi.diagnostic.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object HprofExtensionInstaller {

    private val log = Logger.getInstance(HprofExtensionInstaller::class.java)

    const val SHARK_VERSION = "2.14"
    const val SHARK_SHA256 = "placeholder-sha256-to-be-filled-at-release"
    private val SHARK_URL = "https://repo1.maven.org/maven2/com/squareup/leakcanary/shark/$SHARK_VERSION/shark-$SHARK_VERSION.jar"

    private val installDir: Path = Path.of(System.getProperty("user.home"), ".clawdea", "extensions", "shark-$SHARK_VERSION")
    private val jarPath: Path = installDir.resolve("shark-$SHARK_VERSION.jar")

    fun isInstalled(): Boolean = Files.exists(jarPath)

    fun install(): Boolean {
        try {
            Files.createDirectories(installDir)
            val client = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder(URI.create(SHARK_URL)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofFile(jarPath))
            if (response.statusCode() != 200) {
                log.warn("Failed to download shark: HTTP ${response.statusCode()}")
                Files.deleteIfExists(jarPath)
                return false
            }
            if (SHARK_SHA256 != "placeholder-sha256-to-be-filled-at-release") {
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(Files.readAllBytes(jarPath)).joinToString("") { "%02x".format(it) }
                if (hash != SHARK_SHA256) {
                    log.warn("SHA256 mismatch for shark jar")
                    Files.deleteIfExists(jarPath)
                    return false
                }
            }
            return true
        } catch (e: Exception) {
            log.warn("Failed to install hprof extension", e)
            return false
        }
    }

    fun uninstall() {
        if (Files.exists(installDir)) {
            installDir.toFile().deleteRecursively()
        }
    }

    fun jarPath(): Path? = if (isInstalled()) jarPath else null
}
