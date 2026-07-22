// build.gradle.kts
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion").get())
        bundledPlugin("com.intellij.java")
        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.terminal")
        // Optional Scala plugin from JetBrains Marketplace. Compile classpath only —
        // runtime loading is gated by the optional <depends> in plugin.xml.
        plugin(
            providers.gradleProperty("scalaPluginId").get(),
            providers.gradleProperty("scalaPluginVersion").get(),
        )
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Plugin.Java)
    }

    implementation("com.google.code.gson:gson:2.14.0")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    // Provides kotlinx.coroutines.test.runTest for suspend-function unit tests (steering primitives,
    // cancel-and-continue integration). Pinned to 1.10.2 — NOT the latest — to match the coroutines
    // runtime the target platform (2026.1) bundles; see the note below for why this must track the
    // platform, not upstream latest.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // Required when running LightJavaCodeInsightFixtureTestCase fixtures from the
    // IDE's JUnit runner — UsefulTestCase references opentest4j AssertionFailedError
    // in method signatures, and the IntelliJ Platform test framework no longer
    // brings it in transitively as of 2026.1.
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

// The IntelliJ Platform test sandbox puts the platform's OWN fork of coroutines-core
// (intellij.libraries.kotlinx.coroutines.core.jar, version "1.10.2-intellij-1" — adds
// BuildersKt.runBlockingWithParallelismCompensation for ComponentManagerImpl's service init) on the
// test classpath. kotlinx-coroutines-test transitively pulls in the plain upstream
// kotlinx-coroutines-core-jvm artifact of whatever version it declares. With both present, the flat
// PathClassLoader binds kotlinx.coroutines.BuildersKt to whichever jar's copy loads first —
// nondeterministic across full-suite runs — so ANY second copy on the classpath is a latent bug,
// even a same-version one. Two things are required together, not either alone:
//   1. kotlinx-coroutines-test is pinned to 1.10.2 (above), matching the platform fork's base
//      version, so its calls (plain runBlocking$default) are satisfiable by the platform's fork —
//      1.11.x's TestBuildersJvmKt needs runBlockingK, which only exists upstream, never in the
//      platform fork, so pinning the version is NOT optional even with the exclude below.
//   2. The transitive vanilla kotlinx-coroutines-core-jvm is excluded here so the platform's fork is
//      the ONLY BuildersKt on the classpath — removing the load-order race entirely, rather than
//      hoping the compatible copy wins. Excluded at the configuration level (not per-dependency)
//      because the artifact is published with an `available-at` variant redirect to the separate
//      kotlinx-coroutines-test-jvm module, which a per-dependency exclude() doesn't reliably reach.
configurations.matching { it.name.startsWith("test") }.configureEach {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
}

intellijPlatform {
    pluginConfiguration {
        id = providers.gradleProperty("pluginGroup").get()
        name = providers.gradleProperty("pluginName").get()
        version = providers.gradleProperty("pluginVersion").get()
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }

    // Marketplace signing. Secrets come from environment variables so they
    // never land in the repo. See .github/workflows/publish-plugin.yml for
    // how the GitHub Actions job injects them from repository secrets.
    // Each file path is only set when the matching env var is non-empty, so
    // local `buildPlugin` runs without needing signing material.
    signing {
        val chain = providers.environmentVariable("CERTIFICATE_CHAIN_FILE").orNull
        val key = providers.environmentVariable("PRIVATE_KEY_FILE").orNull
        if (!chain.isNullOrBlank()) certificateChainFile = layout.projectDirectory.file(chain)
        if (!key.isNullOrBlank()) privateKeyFile = layout.projectDirectory.file(key)
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Publish to the "default" (Stable) channel unless the version is a
        // pre-release (contains "-eap", "-beta", "-rc", etc.), in which case
        // publish to a matching channel so stable users do not auto-update.
        channels = providers.gradleProperty("pluginVersion").map { v ->
            val suffix = v.substringAfter('-', "")
            listOf(if (suffix.isEmpty()) "default" else suffix.substringBefore('.'))
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks.test {
    // Fixture-based tests (LightJavaCodeInsightFixtureTestCase) hang when run
    // headlessly via Gradle — they require the full IntelliJ sandbox and should
    // be run from the IDE instead.
    exclude("**/IndexQueryHandlerTest*")
}
