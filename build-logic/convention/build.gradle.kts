plugins {
    `kotlin-dsl`
}

group = "io.galva.sdk.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}


dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    implementation(libs.kover.gradlePlugin)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.maven.publish.gradle)
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
        register("serialization") {
            id = libs.plugins.galva.serialization.get().pluginId
            implementationClass = "SerializationConventionPlugin"
        }
        register("publishing") {
            id = libs.plugins.galva.publishing.get().pluginId
            implementationClass = "MavenPublishingConventionPlugin"
        }
    }
}