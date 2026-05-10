# Profiling

ClawDEA integrates JVM profiling to identify and fix CPU hotspots, memory leaks, and allocation pressure. Claude drives sessions, analyzes recordings, and proposes source-level fixes via `propose_edit`.

Two capture backends share a single `Recording` model: `IntelliJProfilerBackend` (preferred when Ultimate + APIs available) and `JfrBackend` (always available via JDK Flight Recorder). `ProfilerCapabilityProbe` selects at runtime with automatic fallback on API incompatibility.

Three entry points: `/profile` slash command, "Run with ClawDEA Profiler" toolbar action, and `@Test` gutter icon — all funnel through `McpProfilingTools`. Imported `.jfr` and `.hprof` files follow the same analysis path.

## Data flow

```
User → /profile test Foo#bar (or gutter click)
  → ProfileCommandHandler expands prompt → CLI calls profiling_start MCP tool
  → McpProfilingTools.handleStart:
      1. JfrBackend.start() → creates temp .jfc + .jfr, returns sessionId
      2. JfrBackend.buildJvmArgs() → JFR JVM flags
      3. Creates JUnit run config with injected VM args
      4. ProgramRunnerUtil.executeConfiguration() → runs in IDE
      5. ProcessListener.processTerminated() → JfrBackend.stop() → JfrImporter.import()
      6. AnalysisService.register(recording)
  → CLI calls profiling_analyze_cpu / profiling_analyze_allocations
  → CpuHotspotAnalyzer / AllocationHotspotAnalyzer returns hotspot JSON
  → Claude proposes fixes via propose_edit
```

## Related concepts

- [MCP Server](mcp-server.md) — McpProfilingTools registers 8 tools on McpToolRouter
- [CLI Bridge](cli-bridge.md) — /profile slash command forwards expanded prompt to CLI
- [Debug Integration](debug-integration.md) — sibling subsystem using same MCP-exposes-IDE pattern

## Entry points

- [McpProfilingTools.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/mcp/McpProfilingTools.kt) — MCP tool handlers, session lifecycle, process listener attachment
- [JfrBackend.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/capture/jfr/JfrBackend.kt) — JFR session state, JVM args generation, recording collection
- [JfrImporter.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/import/JfrImporter.kt) — parses .jfr events into Recording model
- [AnalysisService.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/analysis/AnalysisService.kt) — recording registry, cached analysis dispatch
- [ProfileCommandHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/commands/ProfileCommandHandler.kt) — /profile slash command prompt templates
- [ProfilingLineMarker.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/ui/ProfilingLineMarker.kt) — @Test gutter icon (ProfileCPU icon, sends /profile test)
- [ProfilingSettings.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/settings/ProfilingSettings.kt) — reads settings from ClawDEASettings state
- [CaptureService.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/capture/CaptureService.kt) — backend selection logic

## MCP tools (8 total)

| Tool | Purpose |
|------|---------|
| `profiling_start` | Launch test/run-config with JFR, return session_id |
| `profiling_stop` | Force-stop and collect recording |
| `profiling_status` | Query session state (STARTING/RUNNING/DONE) |
| `profiling_list` | List all available recordings |
| `profiling_import` | Import a .jfr file for analysis |
| `profiling_analyze_cpu` | CPU hotspot analysis (waits up to 60s for running session) |
| `profiling_analyze_allocations` | Allocation hotspot analysis |
| `profiling_analyze_leaks` | Heap leak analysis (.hprof only) |

## Settings (in ClawDEASettings.State)

All prefixed with `profiling*`: samplingIntervalMs, maxRecordingMb, maxDurationSeconds, stackDepth, backendPreference, maxRecordings, maxStorageGb, autoGitignore, autoAnalyze, topN. UI panel: ClawDEASettingsPanel "Profiling" section.

## Gotchas

- JDK 11+ required on the profiled process (JFR not available in OpenJDK 8).
- JDK 21+ rejects `duration=0` in StartFlightRecording — omit duration and rely on `dumponexit=true`.
- Heap dump analysis requires one-click download of shark extension (~1 MB).
- IntelliJ profiler backend (Ultimate only) produces a lossy Recording (CPU + alloc only) compared to JFR.
- `profiling_analyze_cpu`/`profiling_analyze_allocations` block up to 60s waiting for a running session to finish — this allows Claude to call start then analyze sequentially.
- The gutter icon uses `AllIcons.Actions.ProfileCPU` to distinguish from IntelliJ's native test-run icon.
