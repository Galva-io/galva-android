
import com.android.build.api.dsl.LibraryExtension
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.DeploymentValidation
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension
import java.net.URI

class MavenPublishingConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        apply(plugin = "com.vanniktech.maven.publish")

        group = project.findProperty("GROUP")?.toString() ?: "io.galva.android"
        version = project.findProperty("VERSION_NAME")?.toString()  ?:  project.version.toString()
        val publishTarget = detectPublishTarget(this)
        logger.lifecycle("Configuring publishing for target: $publishTarget")
        pluginManager.withPlugin("com.android.library") {
            logger.lifecycle("Applying environment configuration for publish target: $publishTarget")
            extensions.configure<LibraryExtension> {
                defaultConfig {
                    buildConfigField("String", "ENVIRONMENT", "\"${publishTargetToEnv(publishTarget)}\"")
                }
            }
        }
        configure<MavenPublishBaseExtension> {

            configure(
                AndroidSingleVariantLibrary(
                    variant = "release",
                )
            )

            publishToMavenCentral(validateDeployment = DeploymentValidation.PUBLISHED)
            signAllPublications()

            coordinates(
                groupId = property("GROUP") as String,
                artifactId = project.name,
                version = property("VERSION_NAME") as String,
            )

            pom {
                name.set(stringProperty("POM_NAME"))
                description.set(stringProperty("POM_DESCRIPTION"))
                url.set(stringProperty("POM_URL"))
                inceptionYear.set(stringProperty("POM_INCEPTION_YEAR"))

                licenses {
                    license {
                        name.set(stringProperty("POM_LICENSE_NAME"))
                        url.set(stringProperty("POM_LICENSE_URL"))
                        distribution.set(stringProperty("POM_LICENSE_DIST"))
                    }
                }
                developers {
                    developer {
                        id.set(stringProperty("POM_DEVELOPER_ID"))
                        name.set(stringProperty("POM_DEVELOPER_NAME"))
                        email.set(stringProperty("POM_DEVELOPER_EMAIL"))
                    }
                }
                scm {
                    url.set(stringProperty("POM_SCM_URL"))
                    connection.set(stringProperty("POM_SCM_CONNECTION"))
                    developerConnection.set(stringProperty("POM_SCM_DEV_CONNECTION"))
                }
            }
        }
        configure<PublishingExtension>(){
            repositories {
                maven {
                    name = "Local"
                    url =  uri(
                        rootProject.layout.buildDirectory.dir("repo")
                    )    // → build/repo/
                }
                // GitHub Packages
                maven {
                    name = "GitHubPackages"
                    url  = uri(
                        "https://maven.pkg.github.com/Galva-io/galva-android"
                    )
                    credentials {
                        username = stringProperty("gpr.user")
                            ?: System.getenv("GITHUB_ACTOR")
                                    ?: ""
                        password = stringProperty("gpr.token")
                            ?: System.getenv("GITHUB_TOKEN")
                                    ?: ""
                    }
                }
            }
        }
    }

    private fun Project.stringProperty(name: String): String? =
        findProperty(name) as String?

    private fun detectPublishTarget(project: Project): String {
        // Check explicit override first
        project.findProperty("publishTarget")?.toString()?.let { return it }

        // Detect from the requested tasks
        val taskNames = project.gradle.startParameter.taskNames.map { it.lowercase() }
        return when {
            taskNames.any { it.contains("publishToMavenLocal", ignoreCase = true) } -> "mavenLocal"
            taskNames.any { it.contains("publishallpublicationstomavencentralrepository", ignoreCase = true) } -> "mavenCentral"
            taskNames.any { it.contains("publishToMavenCentral", ignoreCase = true) } -> "mavenCentral"
            taskNames.any { it.contains("publishAllPublicationsToGitHubPackagesRepository", ignoreCase = true) } -> "githubPackages"
            else -> "local"
        }
    }
    private fun publishTargetToEnv(target: String): String = when (target) {
        "mavenLocal" -> "DEVELOPMENT"
        "githubPackages" -> "DEVELOPMENT"
        "mavenCentral" -> "PRODUCTION"
        else -> "DEVELOPMENT"
    }
}