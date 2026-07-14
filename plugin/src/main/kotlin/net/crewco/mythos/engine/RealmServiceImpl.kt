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
            // This used to fail silently, and the player simply stayed where they were — which looked
            // exactly like a broken story rather than a missing world.
            core.logger.warning("Cannot send ${player.name} to '$realmId': world '${realm.worldName}' does not exist. Restart the server.")
            player.sendMessage(mm("<dark_gray><i>The way there is not open. <red>(realm '$realmId' has not generated — tell an admin to restart)"))
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
     * **On Paper**, `WorldCreator.createWorld()` works here and creates (or, on later boots, loads)
     * each realm's world — no restart, no external tooling. This is the normal path.
     *
     * **On Folia**, there is no Bukkit path to a custom world at all: `createWorld()` throws
     * (PaperMC/Folia#134) and a world merely listed in `bukkit.yml` is *not* loaded at boot — so a
     * restart never helps. The only way a custom realm exists on Folia is for a Folia-capable world
     * manager to load it (via NMS); Mythos then **adopts** any world it finds already loaded under
     * the realm's [RealmDefinition.worldName]. To make that adoption reproduce the right terrain we:
     *
     *  1. cache each realm's generator settings to `realms.yml` (read back with no engine present);
     *  2. write `worlds.<name>.generator: Mythos:<realmId>` into `bukkit.yml`, so a manager or a
     *     manual load resolves the generator through [generatorFromCache];
     *  3. try `createWorld()` regardless — it succeeds on Paper and is a harmless no-op-with-warning
     *     on Folia;
     *  4. and for anything that still doesn't exist, say *accurately* what to do — see [reportPending].
     */
    fun createAll() {
        cacheGenerators()
        val pending = ArrayList<RealmDefinition>()

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

            // Persist the generator so anything that DOES load this world — a manager, or a manual
            // bukkit.yml load — reproduces it. Note: this does not, on its own, load the world.
            registerInBukkitYml(realm)

            // Paper: creates (or loads) the world here during startup — no restart, no manager.
            // Folia: Bukkit.createWorld() is unsupported and throws (PaperMC/Folia#134), so this
            // returns null and the world must come from a Folia-capable world manager instead.
            val world = runCatching { creator(realm).createWorld() }
                .onFailure { core.logger.info("Realm '${realm.id}' could not be created at runtime (${it.javaClass.simpleName}).") }
                .getOrNull()

            if (world == null) {
                pending += realm
                return@forEach
            }
            worlds[key] = world
            configure(realm, world)
            core.logger.info("Realm '${realm.id}' → world '${world.name}'")
        }

        if (pending.isNotEmpty()) reportPending(pending)
    }

    /**
     * Tell the admin the truth about what happened — the previous version promised that a restart
     * would fix Folia, which it never does (a world in bukkit.yml is not loaded at boot, and
     * createWorld throws again), so the box printed forever and the realms never appeared.
     */
    private fun reportPending(pending: List<RealmDefinition>) {
        val bar = "+------------------------------------------------------------------+"
        core.logger.warning("")
        core.logger.warning(bar)
        core.logger.warning("|  ${pending.size} REALM(S) COULD NOT BE CREATED:")
        pending.forEach { core.logger.warning("|    ${it.id} -> ${it.worldName}") }
        core.logger.warning("|")
        if (Schedulers.isFolia) {
            core.logger.warning("|  This server is Folia, which does not implement world creation through")
            core.logger.warning("|  the Bukkit API (PaperMC/Folia#134): Bukkit.createWorld() throws, and a")
            core.logger.warning("|  world merely listed in bukkit.yml is NOT loaded at boot. A restart will")
            core.logger.warning("|  NOT create these on its own.")
            core.logger.warning("|")
            core.logger.warning("|  Load their worlds with a Folia-capable world manager (one that loads")
            core.logger.warning("|  worlds via NMS), giving each the generator 'Mythos:<id>' — Mythos will")
            core.logger.warning("|  adopt any world already loaded under the name above. Or run on Paper,")
            core.logger.warning("|  where no manager is needed and realms generate on first boot.")
        } else {
            core.logger.warning("|  Their worlds were registered in bukkit.yml with a 'Mythos:<id>'")
            core.logger.warning("|  generator, but createWorld() failed. Check the log above for the real")
            core.logger.warning("|  cause (a bad material name, a generator error) — that is what stopped")
            core.logger.warning("|  them, and it will recur until it is fixed.")
        }
        core.logger.warning("|")
        core.logger.warning("|  Until a realm exists, anything that sends a player there refuses and")
        core.logger.warning("|  says so, instead of failing silently.")
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
