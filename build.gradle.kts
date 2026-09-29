// Declared once here so every module shares the same Kotlin plugin classloader.
plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.4.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
    id("org.jetbrains.intellij.platform") version "2.18.1" apply false
}

allprojects {
    group = "dev.warin"
    version = providers.gradleProperty("aiCommentsVersion").get()
}
