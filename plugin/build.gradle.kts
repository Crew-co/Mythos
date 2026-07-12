plugins {
    id("com.gradleup.shadow")
    id("net.minecrell.plugin-yml.paper") version "0.6.0"
    id("xyz.jpenilla.run-paper") version "2.3.0"
}

val foliaApiVersion = rootProject.property("foliaApiVersion") as String
val minecraftVersion = foliaApiVersion.substringBefore("-R")

dependencies {
    // `api`, not `implementation`: the addon API's classes must be visible to the
    // addon classloaders, which use the plugin's classloader as their parent.
    api(project(":addon-api"))

    implementation(kotlin("stdlib"))
}

paper {
    name = "Mythos"
    main = "net.crewco.mythos.MythosPlugin"
    apiVersion = "1.21"
    foliaSupported = true // REQUIRED — Folia won't load the plugin without it
    authors = listOf("YourName")
    description = "A Folia plugin template with a dynamic addon system."
}

tasks {
    shadowJar {
        archiveBaseName.set("Mythos")
        archiveClassifier.set("")
        // Do NOT relocate net.crewco.mythos.addon — addons compile against
        // those exact package names; renaming them at runtime breaks every addon.
    }
    build { dependsOn(shadowJar) }
    jar { enabled = false }

    runServer { minecraftVersion(minecraftVersion) }
}

runPaper {
    folia {
        registerTask() // ./gradlew :plugin:runFolia
    }
}
