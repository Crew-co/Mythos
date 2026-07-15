package net.crewco.mythos.engine

import net.crewco.mythos.api.realm.RealmDefinition
import net.crewco.mythos.api.realm.RealmKind
import net.kyori.adventure.key.Key
import net.thenextlvl.worlds.api.WorldsProvider
import net.thenextlvl.worlds.api.generator.Generator
import net.thenextlvl.worlds.api.generator.LevelStem
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.plugin.Plugin
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * **The bridge to the [Worlds](https://modrinth.com/project/gBIw3Gvy) plugin (API 3.12.x) — an
 * optional world manager that can create worlds on Folia, where the Bukkit API cannot.**
 *
 * Folia does not implement runtime world creation (PaperMC/Folia#134), and doing it by hand means
 * reaching into fragile server internals. Worlds already does that, correctly and maintained, so
 * Mythos delegates the one thing it can't do — *loading a world* — to it, and keeps owning the one
 * thing it should — the *rules* of a realm.
 *
 * Worlds is a **soft dependency**: compiled against (`compileOnly net.thenextlvl:worlds:3.12.4`),
 * provided at runtime when installed. This class touches `net.thenextlvl.worlds.api.*` only inside
 * [create], which is called exclusively after [isAvailable] confirms Worlds is present — so those
 * classes are resolved lazily and a server without Worlds never hits a `NoClassDefFoundError`.
 *
 * The `Worlds` plugin instance *is* the [WorldsProvider]. The generator is handed over as
 * `Generator(Mythos, realmId)`, which Worlds resolves by calling Mythos's own
 * `getDefaultWorldGenerator(worldName, realmId)` — the same [RealmServiceImpl.generatorFromCache]
 * path that produces the Void/Olympus/Cavern terrain. So the world comes up with exactly the
 * generation the realm declared.
 */
object WorldsBridge {

    /** True if the Worlds plugin is installed and enabled. Touches none of its classes. */
    fun isAvailable(): Boolean = Bukkit.getPluginManager().getPlugin("Worlds")?.isEnabled == true

    /**
     * Ask Worlds to create (or load, if its data already exists) the realm's world. Returns a future
     * completing with the live world, or null if the request couldn't be issued. Call only when
     * [isAvailable] is true.
     */
    fun create(mythos: Plugin, realm: RealmDefinition): CompletableFuture<World>? {
        // The Worlds plugin implements WorldsProvider — its instance is the API entry point.
        val provider = Bukkit.getPluginManager().getPlugin("Worlds") as? WorldsProvider
        if (provider == null) {
            mythos.logger.warning(
                "Realm '${realm.id}': the Worlds plugin is present but not usable as a WorldsProvider. This almost " +
                    "always means the Worlds API was bundled into Mythos (implementation) rather than compileOnly, or " +
                    "the installed Worlds version differs from the one Mythos compiles against (3.12.x).",
            )
            return null
        }
        return runCatching {
            val id = realm.id.lowercase()

        // All Mythos realms are NORMAL-environment overworlds; the Bukkit generator overrides terrain.
        val levelStem = when (realm.kind) {
            RealmKind.NETHER -> LevelStem.NETHER
            RealmKind.END -> LevelStem.END
            else -> LevelStem.OVERWORLD
        }

        // A relative, single-segment directory resolves under the world container and, being an
        // immediate child, is loaded by Worlds on later boots — so subsequent starts just re-adopt it.
        val level = provider.levelBuilder(Path.of(realm.worldName))
            .key(Key.key("mythos", id))
            .name(realm.worldName)
            .levelStem(levelStem)
            .structures(realm.kind == RealmKind.OVERWORLD)
            // Resolved by Mythos.getDefaultWorldGenerator(worldName, id) → the realm's generator.
            .generator(Generator(mythos, id))
            .build()

            level.createAsync()
        }.onFailure {
            mythos.logger.warning("Realm '${realm.id}': Worlds threw while creating '${realm.worldName}': $it")
        }.getOrNull()
    }
}
