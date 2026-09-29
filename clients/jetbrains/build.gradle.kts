plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // The IDE provides the Kotlin stdlib, so bundling core's copy would clash with it.
    implementation(project(":core")) {
        exclude(group = "org.jetbrains.kotlin")
    }

    intellijPlatform {
        webstorm(providers.gradleProperty("platformVersion"))

        // CommitCheck lives in the VCS API; BooleanCommitOption lives in the impl content module,
        // which is not on the classpath just because the platform is.
        bundledModule("intellij.platform.vcs.impl")

        // Only for tests that check comment detection against real TSX PSI.
        testBundledPlugin("JavaScript")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    // The platform test framework is JUnit 4 based.
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(25)

    compilerOptions {
        jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.ENABLE
        // Older IDEs bundle an older stdlib; 2025.2 ships Kotlin 2.2.
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
}

intellijPlatform {
    projectName = "ai-comments"
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            create("WS", providers.gradleProperty("platformVersion").get())
        }
    }
    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
    }
}

// `./gradlew :jetbrains:runIde -PrunIdeProject=/path/to/repo` opens that project in the sandbox IDE.
tasks.runIde {
    providers.gradleProperty("runIdeProject").orNull?.let { args(it) }
}
