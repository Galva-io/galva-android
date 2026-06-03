plugins {
    alias(libs.plugins.galva.android.library)
    alias(libs.plugins.galva.serialization)
    alias(libs.plugins.galva.publishing)
}
android {
    namespace = "io.galva.sdk"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BASE_API_URL",findProperty("BASE_API_URL") as String )
        buildConfigField("String", "BASE_API_URL_PROD",findProperty("BASE_API_URL_PROD") as String )
        buildConfigField("String", "SDK_VERSION","\"${findProperty("VERSION_NAME") as String}\"" )
    }

}
dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    api(project(":core"))
    compileOnly(libs.billing.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.billing.ktx)
    testImplementation(libs.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}