package net.crewco.mythos.engine

import net.crewco.mythos.api.realm.*
import net.crewco.mythos.command.CommandContext.Companion.mm
import net.crewco.mythos.scheduler.Schedulers
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.generator.ChunkGenerator
import java.io.File
import org.bukkit.Difficulty
import org.bukkit.GameRule
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The cosmos, as actual Bukkit worlds.
 *
 * **Worlds are created exactly once, during startup**, after every addon has registered its
 * realms and before the first player is let in. Folia does not permit creating a world on a
 * region thread while the server is running, and honestly neither should we: the shape of the
 * universe is not a runtime decision.
 */
class RealmServiceImpl(private val core: MythosEngine) : RealmService {

    private val realms = ConcurrentHashMap<String, RealmDefinition>()
    private val worlds = ConcurrentHashMap<String, World>()

    /** Set by the engine once the data folder exists. */
    lateinit var gateways: Gateways

    /** Extra ways in, granted by addons that don't own the realm. OR'd with its own rules. */
    private val granted = ConcurrentHashMap<String, CopyOnWriteArrayList<net.crewco.mythos.api.realm.RealmAccess>>()

    override fun register(realm: RealmDefinition) {
        // Every lookup (realm(), world(), spawnOf(), send()) keys on id.lowercase(), and the
        // generator round-trip through bukkit.yml/realms.yml does too. Normalise once, here, so a
        // realm declared as "Tartarus" isn't silently unreachable by realm("tartarus").
        realms[realm.id.lowercase()] = realm

        // A CAVERN's roof must be ABOVE its floor, or there is no hollow and the generator fills
        // the world solid. The defaults (platformY 200, roofY 120) are tuned for SKY, so a CAVERN
        // that leans on them is inverted. Warn loudly; the generator also clamps as a safety net.
        if (realm.kind == RealmKind.CAVERN && realm.roofY <= realm.platformY) {
            core.logger.warning(
                "Realm '${realm.id}' is a CAVERN whose roofY (${realm.roofY}) is not above its floor " +
                    "(platformY ${realm.platformY}). Set roofY well above platformY, or it will be a very " +
                    "thin cave. Example: platformY = 40, roofY = 100.",
            )
        }
        core.logger.info("Realm '${realm.id}' declared (${realm.kind})")
    }

    override fun grant(realmId: String, access: RealmAccess) {
        granted.getOrPut(realmId.lowercase()) { CopyOnWriteArrayList() } += access
        core.logger.info("A new way into '$realmId' was granted by an addon.")
    }

    override fun realm(id: String) = realms[id.lowercase()]
    override fun realms(): List<RealmDefinition> = realms.values.toList()
    override fun world(realmId: String): World? = worlds[realmId.lowercase()]

    override fun realmOf(world: World): RealmDefinition? =
        worlds.entries.firstOrNull { it.value.uid == world.uid }?.let { realms[it.key] }

    override fun realmOf(player: Player): RealmDefinition? = realmOf(player.world)

    override fun spawnOf(realmId: String): Location? = world(realmId)?.spawnLocation

    override fun mayEnter(player: Player, realmId: String): Boolean {
        val realm = realm(realmId) ?: return false
        val context = contextFor(player)
        if (realm.access.mayEnter(player, context)) return true

        // ...or anything a later addon decided was also a way in. A bough. A helm. A coin.
        // grant() keys on id.lowercase(); match it, or an extra way in silently never fires.
        return granted[realm.id.lowercase()]?.any { it.mayEnter(player, context) } == true
    }

    override fun openGateway(gateway: Gateway) = gateways.open(gateway)

    override fun closeGateway(id: String) = gateways.close(id)

    override fun gateways(): List<Gateway> = gateways.all()

    override fun send(player: Player, realmId: String, reason: String): Boolean {
        val realm = realm(realmId) ?: run {
            core.logger.warning("Something tried to send ${player.name} to realm '$realmId', which nothing declared.")
            return false
        }
        val destination = spawnOf(realmId) ?: run {
            // Failing silently used to look exactly like a broken story rather than a missing world.
            // On Folia a realm may have no world at all (nothing loaded it); say so plainly.
            core.logger.warning("Cannot send ${player.name} to '$realmId': its world '${realm.worldName}' is not loaded.")
            player.sendMessage(mm("<dark_gray><i>The way there is not open. <red>(the realm '$realmId' has no world — tell an admin)"))
            return false
        }

        // teleportAsync is the only cross-region-safe teleport on Folia. Always.
        player.teleportAsync(destination).thenRun {
            core.schedulers.entity(player) {
                if (realm.flight) {
                    player.allowFlight = true
                    player.isFlying = true
                }
                if (reason.isNotBlank()) player.sendMessage(mm("<dark_gray><i>$reason"))
                realm.entryLore.forEach { player.sendMessage(mm(it)) }
            }
        }
        return true
    }


    /**
     * **Build the cosmos.**
     *
     * Called once, at the tail of `onEnable`, after every addon has declared its realms.
     *
     * **On Paper**, `WorldCreator.createWorld()` works and creates (or, on later boots, loads) each
     * realm's world. **On Folia**, that API throws, so Mythos hands the loading to the **Worlds**
     * plugin if it's installed (see [WorldsBridge]) — Worlds does the NMS, Mythos supplies the
     * generator and rules. Failing that, it adopts any world already loaded under the realm's
     * [RealmDefinition.worldName] (e.g. from another world manager). Gaia (PRIMARY) is the overworld,
     * so it is always there.
     *
     * Generator settings are cached to `realms.yml` and registered in `bukkit.yml` so that whichever
     * path loads a world resolves the right terrain via [generatorFromCache]. Realms that still have
     * no world are reported by [reportPending] and refuse entry cleanly until one exists.
     */
    fun createAll() {
        cacheGenerators()
        val pending = ArrayList<RealmDefinition>()

        if (Schedulers.isFolia) {
            core.logger.info(
                if (WorldsBridge.isAvailable()) "Worlds plugin detected — realm worlds will be created through it."
                else "Worlds plugin NOT detected — extra realms will be unavailable. Install Worlds, or a Folia world manager.",
            )
        }

        realms.values.forEach { realm ->
            val key = realm.id.lowercase()

            if (realm.kind == RealmKind.PRIMARY) {
                Bukkit.getWorlds().firstOrNull()?.let { worlds[key] = it }
                return@forEach
            }

            // Already loaded — created earlier in this same startup, or brought online by a world
            // manager. On Folia that manager is the ONLY way a custom world gets loaded, so adopting
            // an already-present world is a first-class path, not just a fast exit.
            Bukkit.getWorld(realm.worldName)?.let {
                worlds[key] = it
                configure(realm, it)
                core.logger.info("Realm '${realm.id}' → world '${it.name}'")
                return@forEach
            }

            // Persist the generator so anything that loads this world through the ordinary Bukkit
            // path (a manager, a manual bukkit.yml load) reproduces it.
            registerInBukkitYml(realm)

            val world = createWorldFor(realm)
            if (world != null) {
                worlds[key] = world
                configure(realm, world)
                core.logger.info("Realm '${realm.id}' → world '${world.name}'")
                return@forEach
            }

            // Paper's API couldn't make it (i.e. we're on Folia). If the Worlds plugin is present,
            // hand the loading off to it — it does the NMS Mythos won't — and adopt the result when
            // it lands. The realm is briefly unavailable until then; send/gateways refuse cleanly.
            if (WorldsBridge.isAvailable()) {
                val future = WorldsBridge.create(core.plugin, realm)
                if (future != null) {
                    core.logger.info("Realm '${realm.id}': asking the Worlds plugin to create '${realm.worldName}'.")
                    future.whenComplete { w, ex -> adoptFromWorlds(realm, key, w, ex) }
                    return@forEach
                }
            }

            pending += realm
        }

        if (pending.isNotEmpty()) reportPending(pending)
    }

    /** Completion handler for a [WorldsBridge] creation — hops onto a world-safe thread to adopt. */
    private fun adoptFromWorlds(realm: RealmDefinition, key: String, world: World?, error: Throwable?) {
        core.schedulers.global {
            if (error == null && world != null) {
                worlds[key] = world
                configure(realm, world)
                core.logger.info("Realm '${realm.id}' → world '${world.name}' (via Worlds)")
            } else {
                val cause = error?.let { (it as? java.util.concurrent.CompletionException)?.cause ?: it }
                core.logger.warning("Realm '${realm.id}': the Worlds plugin could not create '${realm.worldName}': ${cause?.message}")
            }
        }
    }

    /**
     * Create a realm's world — or adopt it, or give up cleanly.
     *
     * **Paper** implements `WorldCreator.createWorld()`, so we use it: the realm generates on first
     * boot and loads with the server thereafter. **Folia** does not (it throws), and Mythos does not
     * try to force it — the world has to be brought online by a Folia-capable world manager, which
     * Mythos then adopts (see [createAll]). If neither happens, this returns null and the realm is
     * simply unavailable, reported by [reportPending].
     */
    private fun createWorldFor(realm: RealmDefinition): World? =
        runCatching { creator(realm).createWorld() }.getOrNull()

    /**
     * Say, accurately, which realms are missing and what to do — never "restart", which does nothing
     * on Folia, and never pretend a realm exists when it doesn't.
     */
    private fun reportPending(pending: List<RealmDefinition>) {
        val bar = "+------------------------------------------------------------------+"
        core.logger.warning("")
        core.logger.warning(bar)
        core.logger.warning("|  ${pending.size} REALM(S) HAVE NO WORLD AND ARE UNAVAILABLE:")
        pending.forEach { core.logger.warning("|    ${it.id} -> ${it.worldName}") }
        core.logger.warning("|")
        if (Schedulers.isFolia) {
            core.logger.warning("|  This is Folia, which cannot create a world at runtime, and Mythos does")
            core.logger.warning("|  not create them itself. The easiest fix: install the 'Worlds' plugin")
            core.logger.warning("|  (modrinth.com/project/gBIw3Gvy) — Mythos will then create these through")
            core.logger.warning("|  it automatically, with the right terrain and rules.")
            core.logger.warning("|")
            core.logger.warning("|  Or load each world yourself with any Folia world manager, giving it the")
            core.logger.warning("|  generator 'Mythos:<id>' (already written to bukkit.yml). Mythos adopts")
            core.logger.warning("|  any world it finds loaded under the name above.")
            core.logger.warning("|")
            core.logger.warning("|  Gaia (the overworld) always works. Only the extra realms need this.")
        } else {
            core.logger.warning("|  createWorld() failed. Check the log above for the real cause (a bad")
            core.logger.warning("|  material name, a generator error) — that is what stopped them.")
        }
        core.logger.warning("|")
        core.logger.warning("|  Until a realm's world exists, anything that sends a player there refuses")
        core.logger.warning("|  and says so, instead of failing silently.")
        core.logger.warning(bar)
        core.logger.warning("")
    }

    private fun creator(realm: RealmDefinition) = WorldCreator(realm.worldName).apply {
        environment(
            when (realm.kind) {
                RealmKind.NETHER -> World.Environment.NETHER
                RealmKind.END -> World.Environment.THE_END
                else -> World.Environment.NORMAL
            },
        )
        generatorFor(realm)?.let { generator(it) }   // ← the companion's. No delegate needed.
        generateStructures(realm.kind == RealmKind.OVERWORLD)
    }

    /** Applied whether the world was made now or loaded from disk on boot. */
    private fun configure(realm: RealmDefinition, world: World) {
        when (realm.kind) {
            RealmKind.VOID -> world.setSpawnLocation(0, 66, 0)
            RealmKind.SKY -> world.setSpawnLocation(0, realm.platformY + 2, 0)
            RealmKind.CAVERN -> world.setSpawnLocation(0, realm.platformY + 2, 0)
            else -> Unit
        }
        if (realm.still) {
            // The Void does not have a Tuesday.
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
            world.setGameRule(GameRule.DO_WEATHER_CYCLE, false)
            world.setGameRule(GameRule.DO_MOB_SPAWNING, false)
            world.time = 18000
            world.difficulty = Difficulty.PEACEFUL
        }
    }

    /**
     * `bukkit.yml` is read at startup, long before any addon has spoken — so the generator has to be
     * able to describe itself from disk. This is that description.
     */
    private fun cacheGenerators() {
        val yaml = YamlConfiguration()
        realms.values.filter { it.kind != RealmKind.PRIMARY }.forEach { realm ->
            val key = realm.id.lowercase()
            yaml.set("$key.kind", realm.kind.name)
            yaml.set("$key.world", realm.worldName)
            yaml.set("$key.platform-y", realm.platformY)
            yaml.set("$key.roof-y", realm.roofY)
            yaml.set("$key.radius", realm.platformRadius)
            yaml.set("$key.material", realm.platformMaterial)
            yaml.set("$key.stone", realm.stone)
        }
        runCatching { yaml.save(File(core.dataFolder, "realms.yml")) }
            .onFailure { core.logger.warning("Could not cache realm generators: ${it.message}") }
    }

    private fun registerInBukkitYml(realm: RealmDefinition) {
        val file = File("bukkit.yml")
        if (!file.exists()) {
            core.logger.warning("No bukkit.yml found — cannot register '${realm.worldName}' for generation.")
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        val id = realm.id.lowercase()
        val path = "worlds.${realm.worldName}.generator"
        if (yaml.getString(path) == "Mythos:$id") return // already there

        yaml.set(path, "Mythos:$id")
        runCatching { yaml.save(file) }
            .onSuccess { core.logger.info("Registered '${realm.worldName}' in bukkit.yml (generator: Mythos:$id)") }
            .onFailure { core.logger.warning("Could not write bukkit.yml: ${it.message}") }
    }


    companion object {

        /**
         * Called by MythosPlugin.getDefaultWorldGenerator at world-load — **before the engine exists**,
         * before any addon has registered anything. It reads the cache written on the previous boot and
         * must not touch a single field of a RealmServiceImpl.
         */
        @JvmStatic
        fun generatorFromCache(file: File, realmId: String): ChunkGenerator? {
            if (!file.exists()) return null
            val yaml = YamlConfiguration.loadConfiguration(file)
            val key = realmId.lowercase()
            if (!yaml.contains(key)) return null

            val kind = runCatching { RealmKind.valueOf(yaml.getString("$key.kind", "VOID")!!) }.getOrNull()
                ?: return null
            val material = runCatching { Material.valueOf(yaml.getString("$key.material", "QUARTZ_BLOCK")!!) }
                .getOrDefault(Material.QUARTZ_BLOCK)
            val stone = runCatching { Material.valueOf(yaml.getString("$key.stone", "DEEPSLATE")!!) }
                .getOrDefault(Material.DEEPSLATE)
            val platformY = yaml.getInt("$key.platform-y", 200)
            val roofY = yaml.getInt("$key.roof-y", 120)
            val radius = yaml.getInt("$key.radius", 24)

            return when (kind) {
                RealmKind.VOID -> VoidGenerator(material, 64, radius)
                RealmKind.SKY -> OlympusGenerator(material, platformY, radius)
                RealmKind.CAVERN -> CavernGenerator(platformY, roofY, stone, material)
                else -> null
            }
        }

        /** The same thing from a live definition — used when runtime creation IS available (Paper). */
        private fun generatorFor(realm: RealmDefinition): ChunkGenerator? {
            val material = runCatching { Material.valueOf(realm.platformMaterial) }
                .getOrDefault(Material.QUARTZ_BLOCK)
            val stone = runCatching { Material.valueOf(realm.stone) }
                .getOrDefault(Material.DEEPSLATE)
            return when (realm.kind) {
                RealmKind.VOID -> VoidGenerator(material, 64, realm.platformRadius)
                RealmKind.SKY -> OlympusGenerator(material, realm.platformY, realm.platformRadius)
                RealmKind.CAVERN -> CavernGenerator(realm.platformY, realm.roofY, stone, material)
                else -> null
            }
        }
    }

    /** What the rules get to look at. Shared by realm access and gateway conditions. */
    fun contextFor(player: Player): RealmContext {
        val role = core.roles.roleOf(player.uniqueId)
        return RealmContext(
            roleId = role?.id,
            tier = role?.tier,
            isSpirit = core.spirits.isSpirit(player.uniqueId),
            flags = core.profiles.profile(player.uniqueId).flags.toMap(),
        )
    }
}
