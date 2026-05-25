# Language support

**Purpose** Per-language hooks (PSI, related-type rendering, file-extension/Language matching) that MCP tools and the context engine consult so they can stay generic across Java, Kotlin, and Scala without hard-wiring language plugins.

## Invariants

- `LanguageSupport.id` is the stable ClawDEA-namespace identifier (`"java"`, `"kotlin"`, `"scala"` — see `LanguageSupport.ID_*` constants). Build-tool dispatch (`BuildTool.compileCommandFor`) keys on this id, **not** on the IntelliJ `Language` object, so it works even when the IntelliJ Language is unavailable (e.g. Scala without the IntelliJ Scala plugin installed) ([LanguageSupport.kt](../../../src/main/kotlin/com/adobe/clawdea/language/LanguageSupport.kt)).
- `LanguageSupport.language` is **lazy** and may return `null` when the language is supported by ClawDEA but the corresponding IntelliJ plugin is absent. Callers must null-check; do not eagerly read `language` at field init time ([JavaLanguageSupport.kt](../../../src/main/kotlin/com/adobe/clawdea/language/JavaLanguageSupport.kt), [KotlinLanguageSupport.kt](../../../src/main/kotlin/com/adobe/clawdea/language/KotlinLanguageSupport.kt), [ScalaLanguageSupport.kt](../../../src/main/kotlin/com/adobe/clawdea/language/ScalaLanguageSupport.kt)).
- `LanguageSupportRegistry` reads return **lock-free volatile snapshots** rebuilt only on writes. `byLanguageIdSnapshot`, `byExtensionSnapshot`, and `allSnapshot` are repopulated inside `synchronized(lock)` after every `register()`. Per-file scanning hot paths (e.g. `CandidateFingerprinter` during indexing) hit O(1) lookups ([LanguageSupportRegistry.kt](../../../src/main/kotlin/com/adobe/clawdea/language/LanguageSupportRegistry.kt)).
- `forPsiFile(psiFile)` tries `Language.id` first, then **falls back to file extension**. The fallback is load-bearing for Scala 3, whose IntelliJ `Language.id` is `"Scala 3"` while ClawDEA registers a single `ScalaLanguageSupport` keyed off `Language.id == "Scala"` plus extensions `scala` and `sc` ([LanguageSupportRegistry.kt](../../../src/main/kotlin/com/adobe/clawdea/language/LanguageSupportRegistry.kt), [ScalaLanguageSupport.kt](../../../src/main/kotlin/com/adobe/clawdea/language/ScalaLanguageSupport.kt)).
- The Scala plugin PSI is reached via the `ScalaPsiBridge` application service. The interface lives on the main classpath; the only implementation that references Scala-plugin types — `ScalaPluginPsiBridge` — is registered exclusively via `clawdea-scala.xml`, which IntelliJ loads only when `org.intellij.scala` is installed (see `<depends optional="true" config-file="clawdea-scala.xml">` in `plugin.xml`). `ScalaLanguageSupport.findRelatedTypes` looks the bridge up via `getService` inside a try/catch and returns `null` when absent ([ScalaPsiBridge.kt](../../../src/main/kotlin/com/adobe/clawdea/language/scala/ScalaPsiBridge.kt), [ScalaPluginPsiBridge.kt](../../../src/main/kotlin/com/adobe/clawdea/language/scala/ScalaPluginPsiBridge.kt), [clawdea-scala.xml](../../../src/main/resources/META-INF/clawdea-scala.xml)).
- Registration happens once per project open via `LanguageSupportInitializer` (`ProjectActivity`) but the registry is **process-wide**. Re-running for additional projects is idempotent — `register()` replaces by `id`, preserving order ([LanguageSupportInitializer.kt](../../../src/main/kotlin/com/adobe/clawdea/language/LanguageSupportInitializer.kt)).

## Resolution pipeline

1. **Plugin init / first project open** — `LanguageSupportInitializer.execute` calls `LanguageSupportRegistry.register(JavaLanguageSupport)`, `register(KotlinLanguageSupport)`, `register(ScalaLanguageSupport)` in that order.
2. **Optional Scala descriptor** — when the IntelliJ Scala plugin is installed, IntelliJ also applies `clawdea-scala.xml`, which registers `ScalaPluginPsiBridge` as the application service implementing `ScalaPsiBridge`. Without the plugin, this descriptor is not applied and the service is unregistered.
3. **Tool-time lookup (extension path)** — e.g. `McpIdeTools.resolveCompileCommand(filePath, project)` extracts the file extension and calls `LanguageSupportRegistry.forFileExtension(ext)`. No PSI is loaded; this is the path used before tier 4 diagnostics run.
4. **Tool-time lookup (PSI path)** — e.g. `IndexCollector` / `PsiCollector` call `LanguageSupportRegistry.forPsiFile(psiFile)`. The registry first checks `Language.id`, then extension, returning a `LanguageSupport` or null.
5. **Language-specific PSI work** — Java's `findRelatedTypes` walks `PsiJavaFile.importList` directly. Scala's `findRelatedTypes` delegates to `ScalaPsiBridge` via `ApplicationManager.getApplication().getService(ScalaPsiBridge::class.java)`. A null bridge (Scala plugin not installed) returns null and the consumer renders no related types.
6. **Build-tool dispatch** — `BuildTool.compileCommandFor(languageSupport, project)` keys on `languageSupport.id` (one of `ID_JAVA`/`ID_KOTLIN`/`ID_SCALA`). See [Build tools](build-tools.md).

## Anti-patterns

- **Reading `language` eagerly at field init** — `Language.findLanguageByID(...)` is wrapped in `by lazy` precisely because headless tests don't have the Java/Kotlin language plugins on the classpath. Eager reads would NPE in tests and slow first-project-open with Language registry lookups.
- **Bypassing the registry by hard-coding language id strings** — every consumer that touches `"java"` / `"kotlin"` / `"scala"` should route through `LanguageSupport.ID_*` constants and the registry. String literals scatter the source of truth and break Scala 3 fallback.
- **Calling `getService(ScalaPsiBridge::class.java)` without try/catch** — some IntelliJ versions throw on unregistered services rather than returning null. `ScalaLanguageSupport.findRelatedTypes` already handles this; new call sites must follow the same pattern.
- **Registering a new language but forgetting `LanguageSupportInitializer`** — the registry is populated only by the initializer; a new `LanguageSupport` object that is never registered is invisible to all lookups.
- **Reading the registry from a static initializer that runs before `LanguageSupportInitializer`** — `CandidateFingerprinter` during indexing can run before the project-startup activity finishes. Lookups must be lazy, not at field init time.

## Source pointers

- [LanguageSupport.kt](../../../src/main/kotlin/com/adobe/clawdea/language/LanguageSupport.kt) — interface, `RelatedType`, `ID_*` constants
- [LanguageSupportRegistry.kt](../../../src/main/kotlin/com/adobe/clawdea/language/LanguageSupportRegistry.kt) — process-wide registry, lock-free snapshots, extension/Language fallback
- [LanguageSupportInitializer.kt](../../../src/main/kotlin/com/adobe/clawdea/language/LanguageSupportInitializer.kt) — `ProjectActivity` that registers built-in implementations
- [JavaLanguageSupport.kt](../../../src/main/kotlin/com/adobe/clawdea/language/JavaLanguageSupport.kt) — Java import-walking via `PsiJavaFile`
- [KotlinLanguageSupport.kt](../../../src/main/kotlin/com/adobe/clawdea/language/KotlinLanguageSupport.kt) — file-extension/`Language` registration only (no related-type rendering yet)
- [ScalaLanguageSupport.kt](../../../src/main/kotlin/com/adobe/clawdea/language/ScalaLanguageSupport.kt) — registers `scala`/`sc` extensions; delegates to optional `ScalaPsiBridge`
- [ScalaPsiBridge.kt](../../../src/main/kotlin/com/adobe/clawdea/language/scala/ScalaPsiBridge.kt) — bridge interface (lives in main classpath)
- [ScalaPluginPsiBridge.kt](../../../src/main/kotlin/com/adobe/clawdea/language/scala/ScalaPluginPsiBridge.kt) — bridge implementation that walks the Scala plugin's PSI; only loaded when `clawdea-scala.xml` is applied
- [clawdea-scala.xml](../../../src/main/resources/META-INF/clawdea-scala.xml) — optional descriptor that registers the bridge service when `org.intellij.scala` is installed
- [plugin.xml](../../../src/main/resources/META-INF/plugin.xml) — `<depends optional="true" config-file="clawdea-scala.xml">org.intellij.scala</depends>` line that wires the optional dependency
