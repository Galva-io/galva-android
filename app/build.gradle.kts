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

        // 2. Fetch the value safely (provide a fallback if missing)
        val apiKey = localProperties.getProperty("GALVA_API_KEY") ?: throw IllegalArgumentException("GALVA_API_KEY not found, please add it to local.properties")
        buildConfigField("String", "GALVA_API_KEY", "\"$apiKey\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation("com.android.billingclient:billing-ktx:8.0.0")
    implementation(libs.material)
    implementation(project(":galva-sdk"))
}