plugins {
    kotlin("jvm") version "2.1.0" apply false
    id("com.gradleup.shadow") version "8.3.11" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = rootProject.property("group") as String
    version = rootProject.property("version") as String

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    dependencies {
        // Provided by the server at runtime — never bundled.
        "compileOnly"("dev.folia:folia-api:${rootProject.property("foliaApiVersion")}")
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21) // Folia requires Java 21
    }
}

/**
 * Publishes the addon API to GitHub Packages so addon projects can compile
 * against it.
 *
 *   ./gradlew publishApi        → GitHub Packages (CI does this on a version tag)
 *   ./gradlew publishApiLocally → your ~/.m2, for testing an addon locally
 */
project(":addon-api") {
    apply(plugin = "maven-publish")

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                artifactId = "mythos-addon-api"
            }
        }
        repositories {
            if (System.getenv("GITHUB_TOKEN") != null) {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/${rootProject.property("githubRepo")}")

                    credentials {
                        username = System.getenv("GITHUB_ACTOR")
                        password = System.getenv("GITHUB_TOKEN")
                    }
                }
            }
        }
    }
}

tasks.register("publishApi") {
    group = "publishing"
    description = "Publishes the addon API to GitHub Packages."
    dependsOn(":addon-api:publishAllPublicationsToGitHubPackagesRepository")
}

tasks.register("publishApiLocally") {
    group = "publishing"
    description = "Publishes the addon API to mavenLocal, for building an addon against a local host."
    dependsOn(":addon-api:publishToMavenLocal")
}
