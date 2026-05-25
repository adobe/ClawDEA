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
    // Required when running LightJavaCodeInsightFixtureTestCase fixtures from the
    // IDE's JUnit runner — UsefulTestCase references opentest4j AssertionFailedError
    // in method signatures, and the IntelliJ Platform test framework no longer
    // brings it in transitively as of 2026.1.
    testImplementation("org.opentest4j:opentest4j:1.3.0")
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
