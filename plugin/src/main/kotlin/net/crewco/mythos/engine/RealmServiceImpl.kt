package net.crewco.mythos.engine

import net.crewco.mythos.api.realm.Gateway
import net.crewco.mythos.api.realm.RealmContext
import net.crewco.mythos.api.realm.RealmDefinition
import net.crewco.mythos.api.realm.RealmKind
import net.crewco.mythos.api.realm.RealmService
import net.crewco.mythos.command.CommandContext.Companion.mm
import org.bukkit.Bukkit
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

    /**
     * Build them. Called by MythosPlugin after every addon's onEnable, still on the main
     * startup thread, which is the only place this is legal.
     */
    fun createAll() {
        realms.values.forEach { realm ->
            val world = runCatching { create(realm) }
                .onFailure { core.logger.warning("Realm '${realm.id}' failed to generate: ${it.message}") }
                .getOrNull() ?: return@forEach

            worlds[realm.id] = world
            core.logger.info("Realm '${realm.id}' → world '${world.name}'")
        }
    }

    private fun create(realm: RealmDefinition): World? {
        if (realm.kind == RealmKind.PRIMARY) {
            // Gaia. Nobody generates the ground; it is already there, and it was there first.
            return Bukkit.getWorlds().firstOrNull()
        }

        Bukkit.getWorld(realm.worldName)?.let { return it } // already on disk from a previous run

        val material = runCatching { Material.valueOf(realm.platformMaterial) }.getOrDefault(Material.QUARTZ_BLOCK)

        val creator = WorldCreator(realm.worldName).apply {
            when (realm.kind) {
                RealmKind.VOID -> {
                    environment(World.Environment.NORMAL)
                    generator(VoidGenerator(material, 64, realm.platformRadius))
                    generateStructures(false)
                }
                RealmKind.SKY -> {
                    environment(World.Environment.NORMAL)
                    generator(OlympusGenerator(material, realm.platformY, realm.platformRadius))
                    generateStructures(false)
                }
                RealmKind.NETHER -> environment(World.Environment.NETHER)
                RealmKind.END -> environment(World.Environment.THE_END)
                RealmKind.OVERWORLD -> environment(World.Environment.NORMAL)
                RealmKind.PRIMARY -> Unit
            }
        }

        val world = creator.createWorld() ?: return null

        if (realm.kind == RealmKind.VOID || realm.kind == RealmKind.SKY) {
            world.setSpawnLocation(0, if (realm.kind == RealmKind.SKY) realm.platformY + 2 else 66, 0)
        }
        if (realm.still) {
            // The Void does not have a Tuesday.
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
            world.setGameRule(GameRule.DO_WEATHER_CYCLE, false)
            world.setGameRule(GameRule.DO_MOB_SPAWNING, false)
            world.time = 18000
            world.difficulty = Difficulty.PEACEFUL
        }
        return world
    }

    // ---- queries -------------------------------------------------------------

    override fun grant(realmId: String, access: net.crewco.mythos.api.realm.RealmAccess) {
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
        val realm = realm(realmId) ?: return false
        val destination = spawnOf(realmId) ?: return false

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
}
