import java.io.FileInputStream
import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
}


android {
    namespace = "io.galva.sample"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "io.galva.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildFeatures {
            buildConfig = true
        }
        val localProperties = Properties()
        val localPropertiesFile = project.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(FileInputStream(localPropertiesFile))
        }

        // From app/local.properties, else the GALVA_API_KEY environment variable. Without one the
        // build still works (CI checks, publishing the SDK); the sample then runs with Galva disabled.
        val apiKey = localProperties.getProperty("GALVA_API_KEY")
            ?: System.getenv("GALVA_API_KEY")
            ?: "".also { logger.warn("GALVA_API_KEY isn't set in app/local.properties: the sample app runs with Galva disabled.") }
        buildConfigField("String", "GALVA_API_KEY", "\"$apiKey\"")

        // Starts an in-process mock backend and swaps in E2EApplication (src/androidTest).
        testInstrumentationRunner = "io.galva.sample.e2e.E2ETestRunner"
    }

    testOptions {
        animationsDisabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Release code (R8, not debuggable) that profilers may attach to, with the performance probe;
        // installs next to the debug app. See scripts/profile.sh.
        create("profiling") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            proguardFiles("proguard-profiling.pro")
            applicationIdSuffix = ".profiling"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    // The performance probe (src/perf) ships in debug (perf suite) and profiling builds only.
    sourceSets {
        getByName("debug").kotlin.srcDir("src/perf/kotlin")
        getByName("profiling").kotlin.srcDir("src/perf/kotlin")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation("com.android.billingclient:billing-ktx:8.0.0")
    implementation(libs.material)
    implementation(project(":galva-sdk"))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.mockwebserver)
}