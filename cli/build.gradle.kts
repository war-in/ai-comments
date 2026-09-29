plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

val generateVersion by tasks.registering {
    val version = project.version.toString()
    val output = layout.buildDirectory.dir("generated/version")
    inputs.property("version", version)
    outputs.dir(output)
    doLast {
        output.get().file("dev/warin/aicomments/cli/Version.kt").asFile.apply {
            parentFile.mkdirs()
            writeText("package dev.warin.aicomments.cli\n\nconst val VERSION = \"$version\"\n")
        }
    }
}

kotlin {
    macosArm64()
    linuxX64()
    linuxArm64()
    // The VS Code extension runs the same commit stripper in Node, through this library.
    js {
        nodejs()
        useCommonJs()
        binaries.library()
        generateTypeScriptDefinitions()
    }

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.executable {
            baseName = "ai-comments"
            entryPoint = "dev.warin.aicomments.cli.main"
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateVersion)
        }
        commonMain.dependencies {
            implementation(project(":core"))
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

/** Copies each release binary into the Claude plugin under the name `hooks/run.sh` resolves. */
val copyBinariesToClaudePlugin by tasks.registering(Copy::class) {
    val targets = mapOf("macosArm64" to "macos-arm64", "linuxX64" to "linux-x64", "linuxArm64" to "linux-arm64")
    targets.forEach { (target, suffix) ->
        from(tasks.named("linkReleaseExecutable${target.replaceFirstChar(Char::uppercase)}")) {
            include("*.kexe")
            rename { "ai-comments-$suffix" }
        }
    }
    into(rootProject.layout.projectDirectory.dir("clients/claude/bin"))
    filePermissions { unix("rwxr-xr-x") }
}

/** Puts the JS library where the VS Code extension imports it from. */
val copyLibraryToVscode by tasks.registering(Sync::class) {
    from(tasks.named("jsProductionLibraryCompileSync"))
    into(rootProject.layout.projectDirectory.dir("clients/vscode/lib"))
}
