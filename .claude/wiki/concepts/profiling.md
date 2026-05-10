# Profiling

ClawDEA integrates JVM profiling to identify and fix CPU hotspots, memory leaks, and allocation pressure. Claude drives sessions, analyzes recordings, and proposes source-level fixes via `propose_edit`.

Two capture backends share a single `Recording` model: `IntelliJProfilerBackend` (preferred when Ultimate + APIs available) and `JfrBackend` (always available via JDK Flight Recorder). `ProfilerCapabilityProbe` selects at runtime with automatic fallback on API incompatibility.

Three entry points: `/profile` slash command, "Run with ClawDEA Profiler" toolbar action, and `@Test` gutter icon — all funnel into `CaptureService`. Imported `.jfr` and `.hprof` files follow the same analysis path.

## Related concepts

- [MCP Server](mcp-server.md) — McpProfilingTools registers 8 tools on McpToolRouter
- [CLI Bridge](cli-bridge.md) — /profile slash command forwards expanded prompt to CLI
- [Debug Integration](debug-integration.md) — sibling subsystem using same MCP-exposes-IDE pattern

## Entry points

- [CaptureService.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/capture/CaptureService.kt)
- [McpProfilingTools.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/mcp/McpProfilingTools.kt)
- [JfrBackend.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/capture/jfr/JfrBackend.kt)
- [JfrImporter.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/import/JfrImporter.kt)
- [ProfileCommandHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/commands/ProfileCommandHandler.kt)

## Gotchas

- JDK 11+ required on the profiled process (JFR not available in OpenJDK 8).
- Heap dump analysis requires one-click download of shark extension (~1 MB).
- IntelliJ profiler backend produces a lossy Recording (CPU + alloc only) compared to JFR.
