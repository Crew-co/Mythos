import net.minecrell.pluginyml.paper.PaperPluginDescription

plugins {
    kotlin("jvm") version "2.1.0"
    id("com.gradleup.shadow") version "8.3.11"
    id("net.minecrell.plugin-yml.paper") version "0.6.0"
    id("xyz.jpenilla.run-paper") version "2.3.0"
    `maven-publish`
}

group = property("group") as String
version = property("version") as String

val foliaApiVersion = property("foliaApiVersion") as String
val minecraftVersion = foliaApiVersion.substringBefore("-R")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven {
        name = "thenextlvlReleases"
        url = uri("https://repo.thenextlvl.net/releases")
    }
}

dependencies {
    // Provided by the server at runtime — never bundled.
    compileOnly("dev.folia:folia-api:$foliaApiVersion")

    // Bundled, since the server doesn't ship Kotlin's runtime.
    implementation(kotlin("stdlib"))

    // The 'Worlds' plugin is an OPTIONAL soft dependency used to create realm worlds on Folia.
    // WorldsBridge imports net.thenextlvl.worlds.api.* directly, so it must be compileOnly, NEVER
    // implementation: shadow bundles an implementation dependency into Mythos.jar, and then the
    // bundled net.thenextlvl.worlds.api.WorldsProvider is a DIFFERENT class from the one the real
    // Worlds plugin implements — so `getPlugin("Worlds") as? WorldsProvider` returns null and every
    // realm world silently fails to create. The runtime class comes from the Worlds plugin itself,
    // reached through the soft serverDependency declared in the `paper { }` block below.
    compileOnly("net.thenextlvl:worlds:3.12.4")

}

kotlin {
    jvmToolchain(21) // Folia requires Java 21
}

paper {
    name = "Mythos"
    main = "net.crewco.mythos.MythosPlugin"
    apiVersion = "1.21"
    foliaSupported = true // REQUIRED — Folia won't load the plugin without it
    authors = listOf("Crew-co")
    description = "A Greek mythology engine: roles, spirits, eras and powers. The myths are addons."

    // Optional: if the Worlds plugin is installed, load it BEFORE Mythos so it's ready in onEnable.
    // Not required — Mythos loads and runs fine without it.
    serverDependencies {
        register("Worlds") {
            load = PaperPluginDescription.RelativeLoadOrder.BEFORE
            required = false
        }
    }
}

// --- The addon API artifact -------------------------------------------------------------------
//
// Addons compile against a small, stable slice of Mythos: the packages below, MINUS the host
// implementations that happen to share those packages. This jar is that slice. Addons must declare
// it `compileOnly` and never shade it — at runtime these classes resolve to the host's own copies
// (they're in the plugin jar too), so a shaded duplicate would break `isAssignableFrom`.
//
// Published as `net.crewco:mythos-addon-api:<version>`.
val apiJar by tasks.registering(Jar::class) {
    archiveBaseName.set("mythos-addon-api")
    archiveClassifier.set("")
    from(sourceSets["main"].output) {
        include("net/crewco/mythos/api/**")
        include("net/crewco/mythos/addon/**")
        include("net/crewco/mythos/command/**")
        include("net/crewco/mythos/hud/**")
        include("net/crewco/mythos/menu/**")
        // Kotlin compiles top-level functions (the `beats { line() }` DSL, and any other package-level
        // declaration in the API) into file-facade classes indexed by META-INF/*.kotlin_module. Ship
        // the classes without that index and a consumer resolves every *class* but none of the
        // top-level *functions* — the exact "unresolved reference: beats" an addon would hit. Keep it.
        include("META-INF/*.kotlin_module")
        // Host implementations live in those packages too — keep them out of the API surface.
        exclude("net/crewco/mythos/addon/AddonClassLoader*")
        exclude("net/crewco/mythos/addon/AddonManager*")
        exclude("net/crewco/mythos/addon/AddonServices*")
        exclude("net/crewco/mythos/addon/HostAddonContext*")
        exclude("net/crewco/mythos/command/CommandManager*")
        exclude("net/crewco/mythos/hud/HostHudService*")
        exclude("net/crewco/mythos/menu/HostMenuService*")
    }
}

publishing {
    publications {
        create<MavenPublication>("api") {
            groupId = project.group.toString()
            artifactId = "mythos-addon-api"
            artifact(apiJar)
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/${property("githubRepo")}")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

// Convenience aliases matching the old multi-module names.
tasks.register("publishApi") {
    group = "publishing"
    description = "Publishes the addon API to GitHub Packages."
    dependsOn("publishApiPublicationToGitHubPackagesRepository")
}
tasks.register("publishApiLocally") {
    group = "publishing"
    description = "Publishes the addon API to mavenLocal, for building an addon locally."
    dependsOn("publishApiPublicationToMavenLocal")
}

tasks {
    shadowJar {
        archiveBaseName.set("Mythos")
        archiveClassifier.set("")
        // Do NOT relocate net.crewco.mythos.addon — addons compile against those exact package
        // names; renaming them at runtime breaks every addon.
    }
    build { dependsOn(shadowJar) }
    jar { enabled = false }

    runServer { minecraftVersion(minecraftVersion) }
}

runPaper {
    folia {
        registerTask() // ./gradlew runFolia
    }
}
