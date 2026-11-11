plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.muse"
version = "2.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Android Studio is based on IntelliJ; pick the build matching your Studio version.
        // Example for a recent Studio: intellij("2024.2.1") style is handled by the platform plugin.
        // If your Studio is older/newer, change this version to match it.
        create("IC", "2024.2.1")
        bundledPlugins("com.intellij.java")
        // FIX for "instrumentCode FAILED: No Java Compiler dependency found":
        // The IntelliJ Platform Gradle Plugin 2.x needs instrumentationTools()
        // in dependencies, otherwise the instrumentCode task cannot run.
        instrumentationTools()
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Muse Sync"
        version = project.version.toString()
        // Allow install on the user's newer Studio (AI-262.9437.185).
        // Without this, the plugin is limited to build 242.* and install fails.
        ideaVersion {
            sinceBuild.set("242")
            untilBuild.set("262.*")
        }
    }
}

// FIX for "Cannot find a Java installation" on Windows:
// Do NOT use a toolchain here. A toolchain makes Gradle search for / download
// a separate JDK 17, which fails when toolchain download repositories are off.
// Instead, compile with whatever JVM Gradle/Studio is already running on
// (Android Studio's own bundled JBR), targeting Java 17 bytecode.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks {
    // Build the installable zip: ./gradlew buildPlugin
    // Output: build/distributions/Muse Sync-1.0.0.zip
    // Install in Studio: Settings -> Plugins -> gear icon -> Install Plugin from Disk
}
