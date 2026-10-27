plugins {
    alias(libs.plugins.galva.android.library)
    alias(libs.plugins.galva.serialization)
    alias(libs.plugins.galva.publishing)
    alias(libs.plugins.kotlinx.binary.compatibility.validator)
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

// The public API, dumped to api/galva-sdk.api: `apiDump` updates it, `apiCheck` (part of `check`)
// fails when the compiled API differs. AGP's built-in Kotlin doesn't apply the kotlin-android plugin
// the validator hooks into, so its tasks are wired here, on the release classes.
val apiFile = layout.projectDirectory.file("api/galva-sdk.api")
val apiBuild = tasks.register<kotlinx.validation.KotlinApiBuildTask>("apiBuild") {
    inputClassesDirs.from(tasks.matching { it.name == "compileReleaseKotlin" })
    outputApiFile.set(layout.buildDirectory.file("api/galva-sdk.api"))
    runtimeClasspath.from(configurations.named("bcv-rt-jvm-cp-resolver"))
}
tasks.register<Copy>("apiDump") {
    from(apiBuild.flatMap { it.outputApiFile })
    into(apiFile.asFile.parentFile)
}
val apiCheck = tasks.register<kotlinx.validation.KotlinApiCompareTask>("apiCheck") {
    projectApiFile.set(apiFile)
    generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
}
tasks.named("check") { dependsOn(apiCheck) }

dependencies {
    // Internal modules: published for the app's runtime, never on its compile classpath. Every type
    // in the public API lives in this module (io.galva.sdk).
    implementation(project(":core"))
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

    // The validator adds this itself only when it finds the kotlin-android plugin (see apiBuild).
    "bcv-rt-jvm-cp"("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
}