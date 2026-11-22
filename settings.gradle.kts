pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BleForge"
include(":app")
include(":core:model")
include(":core:ui")
include(":core:database")
include(":feature:bluetooth")
include(":feature:dashboard")
include(":feature:inspector")
