package net.crewco.mythos.engine

import net.crewco.mythos.api.realm.*
import net.crewco.mythos.command.CommandContext.Companion.mm
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
        realms[realm.id] = realm
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
        return granted[realm.id]?.any { it.mayEnter(player, context) } == true
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
     * And here is the constraint that shapes this entire file: **Folia does not permit creating a
     * world at runtime.** `WorldCreator.createWorld()` throws, and every realm silently failed to
     * generate — which is why, on a fresh server, the spirits of the Age of Chaos were left standing
     * in the overworld instead of the Void, with a warning buried three hundred lines up the log.
     *
     * The only supported path is the one Bukkit has always had: **`bukkit.yml` + a plugin generator**,
     * resolved at world-load, which happens *before* plugins enable. So:
     *
     *  1. we cache each realm's generator settings to `realms.yml` (addons haven't loaded yet next
     *     boot, so the generator has to read them from disk);
     *  2. we write `worlds.<name>.generator: Mythos:<realmId>` into `bukkit.yml`;
     *  3. we *try* runtime creation anyway, because on plain Paper it works and saves a restart;
     *  4. and if anything is still missing, we say so **loudly**, at the top of the log, in a box.
     *
     * One restart on first install. After that they are ordinary worlds that load with the server.
     */
    fun createAll() {
        cacheGenerators()
        val pending = ArrayList<RealmDefinition>()

        realms.values.forEach { realm ->
            if (realm.kind == RealmKind.PRIMARY) {
                Bukkit.getWorlds().firstOrNull()?.let { worlds[realm.id] = it }
                return@forEach
            }

            // Already on disk — loaded at startup from bukkit.yml, or created on a previous Paper run.
            Bukkit.getWorld(realm.worldName)?.let {
                worlds[realm.id] = it
                configure(realm, it)
                core.logger.info("Realm '${realm.id}' → world '${it.name}'")
                return@forEach
            }

            registerInBukkitYml(realm)

            // Paper: this works and we're done. Folia: this throws, and we need a restart.
            val world = runCatching { creator(realm).createWorld() }
                .onFailure { core.logger.info("Realm '${realm.id}' cannot be created at runtime (${it.javaClass.simpleName}) — it will generate on restart.") }
                .getOrNull()

            if (world == null) {
                pending += realm
                return@forEach
            }
            worlds[realm.id] = world
            configure(realm, world)
            core.logger.info("Realm '${realm.id}' → world '${world.name}'")
        }

        if (pending.isEmpty()) return

        // A warning in a log nobody reads is a bug that ships. Say it properly.
        core.logger.warning("")
        core.logger.warning("+------------------------------------------------------------------+")
        core.logger.warning("|  ${pending.size} REALM(S) HAVE NOT GENERATED YET.                            ")
        core.logger.warning("|                                                                  |")
        pending.forEach { core.logger.warning("|    ${it.id} -> ${it.worldName}") }
        core.logger.warning("|                                                                  |")
        core.logger.warning("|  Folia will not create a world while the server is running, so    |")
        core.logger.warning("|  they have been written into bukkit.yml instead.                  |")
        core.logger.warning("|                                                                  |")
        core.logger.warning("|  >>> RESTART THE SERVER. <<<  They will generate on boot, and     |")
        core.logger.warning("|  you will never see this message again.                           |")
        core.logger.warning("|                                                                  |")
        core.logger.warning("|  Until then, anything that sends a player to one of these will    |")
        core.logger.warning("|  do nothing, and the story will look broken. Because it is.       |")
        core.logger.warning("+------------------------------------------------------------------+")
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
            yaml.set("${realm.id}.kind", realm.kind.name)
            yaml.set("${realm.id}.world", realm.worldName)
            yaml.set("${realm.id}.platform-y", realm.platformY)
            yaml.set("${realm.id}.roof-y", realm.roofY)
            yaml.set("${realm.id}.radius", realm.platformRadius)
            yaml.set("${realm.id}.material", realm.platformMaterial)
            yaml.set("${realm.id}.stone", realm.stone)
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
        val path = "worlds.${realm.worldName}.generator"
        if (yaml.getString(path) == "Mythos:${realm.id}") return // already there

        yaml.set(path, "Mythos:${realm.id}")
        runCatching { yaml.save(file) }
            .onSuccess { core.logger.info("Registered '${realm.worldName}' in bukkit.yml (generator: Mythos:${realm.id})") }
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
            if (!yaml.contains(realmId)) return null

            val kind = runCatching { RealmKind.valueOf(yaml.getString("$realmId.kind", "VOID")!!) }.getOrNull()
                ?: return null
            val material = runCatching { Material.valueOf(yaml.getString("$realmId.material", "QUARTZ_BLOCK")!!) }
                .getOrDefault(Material.QUARTZ_BLOCK)
            val stone = runCatching { Material.valueOf(yaml.getString("$realmId.stone", "DEEPSLATE")!!) }
                .getOrDefault(Material.DEEPSLATE)
            val platformY = yaml.getInt("$realmId.platform-y", 200)
            val roofY = yaml.getInt("$realmId.roof-y", 120)
            val radius = yaml.getInt("$realmId.radius", 24)

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
