rootProject.name = "ai-comments"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Downloads the JDK 25 toolchain when none is installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":core", ":cli", ":jetbrains")
project(":jetbrains").projectDir = file("clients/jetbrains")
