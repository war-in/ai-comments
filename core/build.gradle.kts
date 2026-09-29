plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// Common code only: the same grammar, stripper and lexer run in the IDE (JVM), the CLI (native)
// and VS Code (JS), so nothing here may touch java.* or platform APIs.
kotlin {
    jvmToolchain(25)
    jvm()
    macosArm64()
    linuxX64()
    linuxArm64()
    // For the VS Code extension, through the CLI module's JS library.
    js {
        nodejs()
    }

    compilerOptions {
        // Older IDEs bundle an older stdlib, and this code runs on it inside the JetBrains plugin.
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
