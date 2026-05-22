# Profiling

**Purpose** Let Claude profile JVM code via JDK Flight Recorder, then analyze CPU hotspots, allocation pressure, or memory leaks against the captured recording or imported `.jfr` / `.hprof` files.

## Invariants

- **Backend selection is deterministic**, governed by `CaptureService.selectBackend(probe, request)`:
  1. `request.forcedBackend` always wins (test/diagnostic override).
  2. `CaptureTarget.ImportFile` always uses `JFR` (we read the file directly, no live profiling).
  3. If `ProfilerCapabilityProbe.probe() == JFR_ONLY` (e.g. async-profiler not bundled), use `JFR`.
  4. If the request includes any category outside `{CPU, ALLOCATIONS}`, use `JFR` (heap-leak requires `.hprof`, not async-profiler).
  5. Otherwise use `INTELLIJ` (the bundled async-profiler-backed IntelliJ profiler) for lower overhead. The order matters; reordering changes which profiler runs ([CaptureService.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/capture/CaptureService.kt)).
- **Heap-leak analysis requires a `.hprof`**, not a `.jfr`. `profiling_analyze_leaks` rejects `.jfr` recordings with a clear error rather than silently producing empty results ([McpProfilingTools.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/mcp/McpProfilingTools.kt)).
- **`profiling_analyze_*` tools block until the recording is fully analyzed**, then return up to `top_n` hotspots. `profiling_status` is non-blocking and exists only for live-session polling — analysis tools should be called directly because they internally wait for completion ([McpProfilingTools.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/mcp/McpProfilingTools.kt)).
- **Imported recordings get a stable `recording_id`** assigned by `ProfileStorage`. Subsequent analysis tools take that id; `profiling_list` returns all stored recordings with their metadata ([ProfileStorage.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/import/ProfileStorage.kt), [McpProfilingTools.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/mcp/McpProfilingTools.kt)).
- **The `INTELLIJ` backend covers only `CPU` and `ALLOCATIONS`**. The `INTELLIJ_SUPPORTED` set is the contract — adding a new category to capture without updating that set silently routes to JFR even when the user expected the bundled profiler ([CaptureService.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/capture/CaptureService.kt)).

## Resolution pipeline

1. **Entry points**: `/profile` slash command, gutter icon on `@Test` methods, or `profiling_import` for an existing `.jfr` / `.hprof` file. All three resolve to a `CaptureRequest` with `target` (run config / test / pid / import file) and `categories`.
2. **Backend selection** — `CaptureService.selectBackend(probe, request)` (see invariants).
3. **Capture** (live profiling only):
   - `INTELLIJ` backend → IntelliJ's async-profiler runner; results streamed as they accumulate.
   - `JFR` backend → `JfrBackend` writes a `.jfr` file via `jcmd` or attached agent, configured by `JfcConfigGenerator` per category set.
4. **Persist** — `ProfileStorage.save(recording)` assigns a `recording_id` and stores the file with metadata (target, categories, timestamp, optional note).
5. **Analyze** — caller invokes one of:
   - `profiling_analyze_cpu(recording_id, thread_filter?, top_n?)` → `CpuHotspotAnalyzer` walks frames, aggregates by `(class, method)`, returns top samples.
   - `profiling_analyze_allocations(recording_id, class_filter?, top_n?)` → `AllocationHotspotAnalyzer` walks allocation events, aggregates by `(class, allocation site)`.
   - `profiling_analyze_leaks(recording_id, top_n?)` → `.hprof` only; computes dominator-tree retained sizes.
6. **Source resolution** — `SourceResolver` maps frame `(class, method)` to file + line via the IntelliJ index so the result includes clickable refs the chat panel can link.

## Anti-patterns

- **Using `profiling_status` to wait for analysis** — Status is non-blocking and intended for live-session UIs; analysis tools already block until done. Polling status spins the dispatch executor and adds latency.
- **Calling `profiling_analyze_leaks` on a `.jfr`** — The method rejects with a clear error rather than producing empty results. The CLI should not retry; it should re-import as `.hprof` or pick a different analysis.
- **Forcing the `JFR` backend for CPU-only on a JFR-only host** — That's already what `selectBackend` does; an explicit `forcedBackend` override should only be used by tests or operator diagnostics, never by routine profiling code paths.
- **Caching analysis results across recordings with the same id** — `ProfileStorage` re-uses ids only for re-imported files; the underlying recording bytes can change. Analyzers must read from storage, not memoize on id.

## Source pointers

- [CaptureService.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/capture/CaptureService.kt) — backend selection
- [CaptureBackend.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/capture/CaptureBackend.kt) — `BackendType`, `Category`, `CaptureRequest`, `CaptureTarget`
- [JfrBackend.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/capture/jfr/JfrBackend.kt) / [JfcConfigGenerator.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/capture/jfr/JfcConfigGenerator.kt) — JFR capture path
- [ProfilerCapabilityProbe.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/capability/ProfilerCapabilityProbe.kt) — bundled-profiler vs JFR-only detection
- [JfrImporter.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/import/JfrImporter.kt) / [HprofImporter.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/import/HprofImporter.kt) / [ProfileStorage.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/import/ProfileStorage.kt) — file import + storage
- [HprofExtensionInstaller.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/extensions/HprofExtensionInstaller.kt) — registers `.hprof` file extension support
- [AnalysisService.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/analysis/AnalysisService.kt) — orchestrates analyzers
- [CpuHotspotAnalyzer.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/analysis/CpuHotspotAnalyzer.kt) / [AllocationHotspotAnalyzer.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/analysis/AllocationHotspotAnalyzer.kt) — analyzers
- [SourceResolver.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/analysis/SourceResolver.kt) — frame → file/line resolution via PSI
- [McpProfilingTools.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/mcp/McpProfilingTools.kt) — MCP tool entry points (`profiling_start`, `_stop`, `_status`, `_list`, `_import`, `_analyze_cpu`, `_analyze_allocations`, `_analyze_leaks`)
- [ProfileCommandHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/profiling/commands/ProfileCommandHandler.kt) — `/profile` slash command
