plugins {
    alias(libs.plugins.galva.android.library)
    alias(libs.plugins.galva.android.room)
}
android {
    namespace = "io.galva.android.identity"
}
dependencies{
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}