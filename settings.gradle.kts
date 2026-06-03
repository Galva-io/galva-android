pluginManagement {
    includeBuild("build-logic")
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
        mavenLocal()
    }
}

rootProject.name = "galva-android-sdk"
include(":app")
include(":galva-sdk")
include(":core")
include(":identity")
include(":local-storage")
include(":common")
include(":network")
include(":billing")
include(":operation-queue")
include(":inapp-message")