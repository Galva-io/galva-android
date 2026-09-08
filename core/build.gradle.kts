plugins {
    alias(libs.plugins.galva.android.library)
    alias(libs.plugins.galva.serialization)
    alias(libs.plugins.galva.publishing)
}
android {
    namespace = "io.galva.sdk.core"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BASE_API_URL",findProperty("BASE_API_URL") as String )
        buildConfigField("String", "BASE_API_URL_PROD",findProperty("BASE_API_URL_PROD") as String )
    }
}
dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    api(project(":identity"))
    api(project(":operation-queue"))
    api(project(":local-storage"))
    api(project(":billing"))
    api(project(":common"))
    api(project(":network"))
    api(project(":inapp-message"))

    implementation(libs.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}