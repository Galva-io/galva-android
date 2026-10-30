plugins{
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

allprojects {
    group = property("GROUP") as String
    version = property("VERSION_NAME") as String
}
