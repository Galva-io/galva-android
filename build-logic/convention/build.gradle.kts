plugins {
    `kotlin-dsl`
}

group = "io.galva.android.sdk.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}


dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.room.gradlePlugin)
}
gradlePlugin {
    plugins {
        register("androidApplication") {
            id = libs.plugins.galva.android.application.get().pluginId
            implementationClass = "AndroidApplicationConventionPlugin"
        }

        register("androidLibrary") {
            id = libs.plugins.galva.android.library.get().pluginId
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidRoom") {
            id = libs.plugins.galva.android.room.get().pluginId
            implementationClass = "AndroidRoomConventionPlugin"
        }
    }
}