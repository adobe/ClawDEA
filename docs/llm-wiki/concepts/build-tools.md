# Build tools

**Purpose** Adapt project-level Gradle / Maven / sbt / Mill so that the [MCP server](mcp-server.md)'s tier-4 `get_diagnostics` path can run a real compile, and so that primer/context can surface build-config files to the model.

## Invariants

- Every adapter implements `BuildTool` ([BuildTool.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/BuildTool.kt)) with four hooks: `isActive(project)`, `buildConfigFiles(project)`, `compileCommandFor(languageSupport, project)`, `filterDiagnostics(output, targetFile, basePath)`. Adapters are stateless `object` singletons.
- `BuildToolRegistry.detectPrimary(project)` returns the **first** `BuildTool` (in registration order) whose `isActive(project)` is true; `detectAll(project)` returns all active tools, preserving registration order ([BuildToolRegistry.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/BuildToolRegistry.kt)).
- Registration order — set by `BuildToolInitializer.execute` — is **Gradle, Maven, sbt, Mill**. This is the tie-break order when multiple build tools are simultaneously active at the same base path. Gradle wins because it is the historical default ClawDEA assumed ([BuildToolInitializer.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/BuildToolInitializer.kt)).
- Detection prefers IntelliJ's `ExternalSystem` (e.g. `"GRADLE"`, `"Maven"`, `"SBT"`) and falls back to project-base marker files. Mill has no dedicated `ExternalSystem` id and is detected by markers only (`build.mill`, `build.mill.scala`, `build.sc`) — generic BSP is also used by sbt/Bloop, so it can't be attributed to Mill ([GradleBuildTool.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/GradleBuildTool.kt), [MavenBuildTool.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/MavenBuildTool.kt), [SbtBuildTool.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/SbtBuildTool.kt), [MillBuildTool.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/MillBuildTool.kt)).
- `compileCommandFor(languageSupport, project)` keys on `LanguageSupport.id` (`"java"` / `"kotlin"` / `"scala"`), **not** on `Language` identity. This is the seam that lets ClawDEA dispatch a Scala compile even when the IntelliJ Scala plugin is not installed — see [Language support](language-support.md). A `null` return means "this build tool cannot compile this language in this project" and the caller falls back ([BuildTool.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/BuildTool.kt)).
- Maven gates non-Java languages on root-pom plugin presence: Java is always allowed; Kotlin requires `org.jetbrains.kotlin:kotlin-maven-plugin`; Scala requires `net.alchim31.maven:scala-maven-plugin`. The **effective-pom** (deep inheritance) is not resolved — only the root pom is inspected ([MavenBuildTool.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/MavenBuildTool.kt)).
- The pom tree (root pom + recursive `<modules>` children) is cached per-project in a `WeakHashMap`. Cache freshness is verified by stat-ing each cached pom's `lastModified()`; chat-context calls hit the stat-only fast path and only re-walk on real change ([MavenBuildTool.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/MavenBuildTool.kt), [PomReader.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/maven/PomReader.kt)).
- `PomReader` uses a DOM parser configured to be **XXE-safe** (no external entities, no DTDs, no XInclude). All read methods return empty/false on parse failure rather than throwing ([PomReader.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/maven/PomReader.kt)).

## Resolution pipeline

1. **Plugin init / first project open** — `BuildToolInitializer` (a `ProjectActivity`) calls `BuildToolRegistry.register(...)` for `GradleBuildTool`, `MavenBuildTool`, `SbtBuildTool`, `MillBuildTool` in that exact order. Re-runs on subsequent project opens are idempotent.
2. **Tier-4 diagnostics dispatch** — `McpIdeTools.resolveCompileCommand(filePath, project)`:
   1. `BuildToolRegistry.detectPrimary(project)` — returns the first active `BuildTool` or yields the `NO_BUILD_TOOL_MSG` early result.
   2. `LanguageSupportRegistry.forFileExtension(ext)` — resolves the file extension to a `LanguageSupport`, or yields `unknownExtensionMsg(ext)`.
   3. `buildTool.compileCommandFor(languageSupport, project)` — returns a `CompileCommand(argv, workingDir, timeout=30s)` or yields `unsupportedLanguageMsg(...)` when the build tool does not support that language.
3. **Subprocess** — `McpIdeTools` spawns `ProcessBuilder(argv).directory(workingDir)` inside the tier-3+4 budget envelope (55s total under the 60s MCP cap, with a 5s floor reserved for tier 4). Stdout+stderr are captured.
4. **Output filtering** — `buildTool.filterDiagnostics(output, targetFile, basePath)` keeps only lines that mention the target file (relative or absolute path) and a tool-specific severity marker (e.g. `[error]`, `[ERROR]`). The filtered text becomes the `ToolResult` content.
5. **Multi-build-tool projects (`FileCollector`)** — primer/context iteration uses `BuildToolRegistry.detectAll(project)` and reads `buildConfigFiles` from each, so a polyglot project (e.g. Maven root + Mill subdir) surfaces both sets of build files even though `detectPrimary` would only pick one for compile dispatch ([FileCollector.kt](../../../src/main/kotlin/com/adobe/clawdea/context/FileCollector.kt)).

## Per-tool launcher policy

| Tool | Detection | Launcher | Compile argv |
|---|---|---|---|
| Gradle | ExternalSystem `"GRADLE"` or `build.gradle{,.kts}` / `settings.gradle{,.kts}` | `./gradlew` only — no PATH fallback (avoids JDK/version ambiguity) | `./gradlew <task> --quiet` where `<task>` is `compileJava` / `compileKotlin` / `compileScala` |
| Maven | ExternalSystem `"Maven"` or `pom.xml` | `./mvnw` if present, else `mvn` on PATH | `<launcher> compile -q` |
| sbt | ExternalSystem `"SBT"` or `build.sbt` | `sbt` on PATH | `sbt -batch -no-colors compile` (compiles `.scala` and `.java` together) |
| Mill | `build.mill` / `build.mill.scala` / `build.sc` | `./mill` if executable, else `mill` on PATH | `<launcher> --no-server __.compile` (`__` is Mill's recursive task selector) |

## Anti-patterns

- **Adding a new build tool but forgetting registration order** — `BuildToolInitializer` is the source of truth for tie-break order. A new adapter inserted at the wrong position can change `detectPrimary` for projects that have multiple active build tools.
- **Hard-coding a language id outside `LanguageSupport.ID_*`** — adapters must dispatch on `languageSupport.id`, comparing against `LanguageSupport.ID_JAVA` / `ID_KOTLIN` / `ID_SCALA`. String literals scatter the source of truth and break the Scala-without-plugin path.
- **Resolving the Maven effective-pom for plugin detection** — currently out of scope. Inherited Kotlin/Scala plugins from a parent pom will be missed and `compileCommandFor` will return null for those languages. Document the limit; do not silently change it.
- **Falling back to a system-PATH `gradle`** — Gradle's adapter intentionally uses `./gradlew` only. A PATH fallback would introduce JDK/version ambiguity; if the wrapper is missing the subprocess fails at start and the caller gets a clean error.
- **Relying on `ExternalSystem` for Mill** — generic BSP is shared with sbt and Bloop. Mill detection is marker-file-only; do not rewire it to BSP without distinguishing the BSP server identity.

## Source pointers

- [BuildTool.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/BuildTool.kt) — interface
- [BuildToolRegistry.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/BuildToolRegistry.kt) — process-wide registry, `detectPrimary` / `detectAll`
- [BuildToolInitializer.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/BuildToolInitializer.kt) — `ProjectActivity` that fixes registration order
- [BuildToolDetection.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/BuildToolDetection.kt) — shared `ExternalSystem` and marker-file helpers
- [CompileCommand.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/CompileCommand.kt) — argv + workingDir + 30s default timeout
- [GradleBuildTool.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/GradleBuildTool.kt), [MavenBuildTool.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/MavenBuildTool.kt), [SbtBuildTool.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/SbtBuildTool.kt), [MillBuildTool.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/MillBuildTool.kt) — adapters
- [PomReader.kt](../../../src/main/kotlin/com/adobe/clawdea/buildtool/maven/PomReader.kt) — XXE-safe DOM reader for pom.xml introspection
- [McpIdeTools.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpIdeTools.kt) — `resolveCompileCommand` is the only consumer of `detectPrimary`; tier-4 dispatch lives here
- [FileCollector.kt](../../../src/main/kotlin/com/adobe/clawdea/context/FileCollector.kt) — only consumer of `detectAll` (primer/context)
