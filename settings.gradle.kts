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
