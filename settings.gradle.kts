pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FPInk"
include(":app")
include(":core:model")
include(":core:ai")
include(":core:storage")
include(":recognition:paddle")

// Private-companion-repo modules (Azure/MyScript), only present when the `private/`
// submodule has been checked out locally. Absent in public clones, CI, and F-Droid.
listOf("azure", "myscript").forEach { name ->
    val moduleDir = file("private/recognition/$name")
    if (moduleDir.resolve("build.gradle.kts").exists()) {
        include(":recognition:$name")
        project(":recognition:$name").projectDir = moduleDir
    }
}
