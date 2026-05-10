package com.adobe.clawdea.profiling.capture.jfr

import com.adobe.clawdea.profiling.capture.*
import com.adobe.clawdea.profiling.`import`.JfrImporter
import com.adobe.clawdea.profiling.model.Recording
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class JfrBackend(private val project: Project) : CaptureBackend {

    override val type = BackendType.JFR

    override fun isAvailable(): Boolean = true

    override fun supports(categories: Set<Category>): Boolean = true

    private val sessions = ConcurrentHashMap<String, JfrSession>()

    fun start(request: CaptureRequest, settings: ProfilingSessionSettings): String {
        val sessionId = UUID.randomUUID().toString().take(12)
        val jfrPath = Files.createTempFile("clawdea-$sessionId-", ".jfr")
        val jfcPath = Files.createTempFile("clawdea-$sessionId-", ".jfc")
        Files.writeString(jfcPath, JfcConfigGenerator.generate(request.categories, settings.samplingIntervalMs))

        val session = JfrSession(
            id = sessionId,
            jfrPath = jfrPath,
            jfcPath = jfcPath,
            state = SessionState.STARTING,
        )
        sessions[sessionId] = session
        return sessionId
    }

    fun buildJvmArgs(sessionId: String): List<String> {
        val session = sessions[sessionId] ?: return emptyList()
        return listOf(
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+DebugNonSafepoints",
            "-XX:StartFlightRecording=settings=${session.jfcPath},filename=${session.jfrPath},dumponexit=true",
            "-XX:FlightRecorderOptions=stackdepth=128",
        )
    }

    fun markRunning(sessionId: String) {
        sessions[sessionId]?.state = SessionState.RUNNING
    }

    fun stop(sessionId: String): Recording? {
        val session = sessions[sessionId] ?: return null
        session.state = SessionState.STOPPING
        val recording = if (Files.exists(session.jfrPath) && Files.size(session.jfrPath) > 0) {
            JfrImporter.import(session.jfrPath)
        } else {
            null
        }
        session.state = SessionState.DONE
        Files.deleteIfExists(session.jfcPath)
        return recording
    }

    fun getState(sessionId: String): SessionState? = sessions[sessionId]?.state

    private data class JfrSession(
        val id: String,
        val jfrPath: Path,
        val jfcPath: Path,
        var state: SessionState,
    )
}

data class ProfilingSessionSettings(
    val samplingIntervalMs: Int = 10,
    val maxRecordingBytes: Long = 500L * 1024 * 1024,
    val maxDurationSeconds: Int = 900,
    val stackDepth: Int = 128,
)
