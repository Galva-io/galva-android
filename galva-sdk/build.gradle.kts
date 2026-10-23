plugins {
    alias(libs.plugins.galva.android.library)
    alias(libs.plugins.galva.serialization)
    alias(libs.plugins.galva.publishing)
}
android {
    namespace = "io.galva.sdk"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION","\"${findProperty("VERSION_NAME") as String}\"" )
    }

}
// Every public declaration of the facade module is deliberate API.
configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
    explicitApi()
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    api(project(":core"))
    compileOnly(libs.billing.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.process)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.billing.ktx)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}