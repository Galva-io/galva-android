plugins {
    alias(libs.plugins.galva.android.library)
    alias(libs.plugins.galva.serialization)
    alias(libs.plugins.galva.publishing)
}
android {
    namespace = "io.galva.sdk.core"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}
dependencies {
    implementation(libs.kotlinx.coroutines.core)

    api(project(":identity"))
    api(project(":operation-queue"))
    api(project(":local-storage"))
    api(project(":billing"))
    api(project(":common"))
    api(project(":network"))
    api(project(":inapp-message"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}