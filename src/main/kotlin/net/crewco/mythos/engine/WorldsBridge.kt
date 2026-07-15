package net.crewco.mythos.engine

import net.crewco.mythos.api.realm.RealmDefinition
import net.crewco.mythos.api.realm.RealmKind
import net.kyori.adventure.key.Key
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture

/**
 * **The bridge to the [Worlds](https://modrinth.com/project/gBIw3Gvy) plugin — an optional world
 * manager that can create worlds on Folia, where the Bukkit API cannot.**
 *
 * Folia does not implement runtime world creation (PaperMC/Folia#134), and doing it by hand means
 * reaching into fragile server internals. Worlds already does that, correctly and maintained, so
 * Mythos delegates the one thing it can't do — *loading a world* — to it, and keeps owning the one
 * thing it should — the *rules* of a realm.
 *
 * This talks to Worlds purely by **reflection**, so Mythos needs no compile-time dependency on it
 * (no repository, no version to pin) and no `NoClassDefFoundError` when it's absent. The Worlds API
 * it touches — `WorldsAccess.access()`, `Level.builder(key)…build()`, `Generator.of(plugin, id)`,
 * `access.create(level)` — is small and stable, unlike NMS, so this stays robust across versions.
 *
 * The generator is handed over as `Generator.of(Mythos, realmId)`, which Worlds resolves by calling
 * Mythos's own `getDefaultWorldGenerator(worldName, realmId)` — the same
 * [RealmServiceImpl.generatorFromCache] path that produces the Void/Olympus/Cavern terrain.
 */
object WorldsBridge {

    /** True if the Worlds plugin is installed and enabled. Loads none of its classes. */
    fun isAvailable(): Boolean = Bukkit.getPluginManager().getPlugin("Worlds")?.isEnabled == true

    /**
     * Ask Worlds to create (or load, if its data already exists) the realm's world. Returns a future
     * completing with the live world, or null if the request couldn't be issued. Call only when
     * [isAvailable].
     */
    @Suppress("UNCHECKED_CAST")
    fun create(mythos: Plugin, realm: RealmDefinition): CompletableFuture<World>? = runCatching {
        val worlds = Bukkit.getPluginManager().getPlugin("Worlds") ?: return null
        val cl = worlds.javaClass.classLoader

        val accessClass = Class.forName("net.thenextlvl.worlds.WorldsAccess", true, cl)
        val levelClass = Class.forName("net.thenextlvl.worlds.Level", true, cl)
        val builderClass = Class.forName("net.thenextlvl.worlds.Level\$Builder", true, cl)
        val dimensionClass = Class.forName("net.thenextlvl.worlds.Dimension", true, cl)
        val generatorClass = Class.forName("net.thenextlvl.worlds.generator.Generator", true, cl)

        val id = realm.id.lowercase()
        val access = accessClass.getMethod("access").invoke(null)

        // Level.builder(Key.key("mythos", id)) -> Builder
        val builder = levelClass.getMethod("builder", Key::class.java)
            .invoke(null, Key.key("mythos", id))

        // .dimension(Dimension.X)  — all Mythos realms are NORMAL-environment overworlds
        val dimField = when (realm.kind) {
            RealmKind.NETHER -> "THE_NETHER"
            RealmKind.END -> "THE_END"
            else -> "OVERWORLD"
        }
        val dimension = dimensionClass.getField(dimField).get(null)
        builderClass.getMethod("dimension", dimensionClass).invoke(builder, dimension)

        // .structures(Boolean)
        builderClass.getMethod("structures", java.lang.Boolean::class.java)
            .invoke(builder, java.lang.Boolean.valueOf(realm.kind == RealmKind.OVERWORLD))

        // .generator(Generator.of(mythos, id))  — resolves via Mythos.getDefaultWorldGenerator
        val generator = generatorClass.getMethod("of", Plugin::class.java, String::class.java)
            .invoke(null, mythos, id)
        builderClass.getMethod("generator", generatorClass).invoke(builder, generator)

        // .build(), then access.create(level) -> CompletableFuture<World>
        val level = builderClass.getMethod("build").invoke(builder)
        accessClass.getMethod("create", levelClass).invoke(access, level) as CompletableFuture<World>
    }.getOrNull()
}
