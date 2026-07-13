package net.crewco.mythos.engine

import net.crewco.mythos.MythosPlugin
import net.crewco.mythos.addon.AddonSchedulers
import net.crewco.mythos.addon.AddonServices
import net.crewco.mythos.api.era.EraService
import net.crewco.mythos.api.ext.ExtensionService
import net.crewco.mythos.api.power.PowerService
import net.crewco.mythos.api.profile.ProfileService
import net.crewco.mythos.api.role.RoleService
import net.crewco.mythos.api.spirit.SpiritService
import net.crewco.mythos.api.story.ChronicleService
import net.crewco.mythos.api.story.NarratorService
import net.crewco.mythos.engine.commands.ChronicleCommand
import net.crewco.mythos.engine.commands.ClaimCommand
import net.crewco.mythos.engine.commands.EraCommand
import net.crewco.mythos.engine.commands.MythosAdminCommand
import net.crewco.mythos.engine.commands.PowerCommand
import net.crewco.mythos.engine.commands.RoleCommand
import net.crewco.mythos.engine.commands.RolesCommand
import net.crewco.mythos.engine.commands.SpiritCommand
import net.crewco.mythos.hud.HudService
import net.crewco.mythos.menu.MenuService
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * **Mythos is the engine.** Not an addon — the plugin.
 *
 * It knows about *roles* that can be claimed and lost, *spirits* who queue for them,
 * *eras* that turn when their objectives are struck, *powers* that roles grant, and
 * *profiles* that remember all of it. There is no Greek mythology anywhere in here:
 * Chaos, Kronos and Achilles are addons, and they are the only things that are.
 *
 * Everything it publishes lives in `addon-api` (`net.crewco.mythos.api.*`), so a story
 * addon compiles against ONE artifact and declares no dependencies but the host itself.
 *
 * Startup order in [MythosPlugin]: platform services → **engine** → addons. By the time
 * a story addon's `onEnable` runs, `Mythos.from(context)` already resolves.
 */
class MythosEngine(val plugin: MythosPlugin) {

    val logger: Logger get() = plugin.logger
    val dataFolder: File get() = plugin.dataFolder

    /** The host's own Folia schedulers — the same object addons are handed. */
    val schedulers: AddonSchedulers get() = plugin.schedulers

    val menus: MenuService get() = plugin.menus
    val hud: HudService get() = plugin.hud

    lateinit var config: CoreConfig
        private set

    lateinit var storage: Storage
        private set

    lateinit var profiles: ProfileServiceImpl
        private set

    lateinit var roles: RoleServiceImpl
        private set

    lateinit var spirits: SpiritServiceImpl
        private set

    lateinit var eras: EraServiceImpl
        private set

    lateinit var powers: PowerServiceImpl
        private set

    lateinit var display: MythosHud
        private set

    /** Stages scenes: timed beats, titles, sounds. Era transitions run through this. */
    lateinit var narrator: NarratorImpl
        private set

    /** The history of the world, written as it happens. */
    lateinit var chronicle: ChronicleImpl
        private set

    /** How one story reaches into another. Load order doesn't matter. */
    lateinit var extensions: ExtensionServiceImpl
        private set

    fun enable() {
        plugin.saveDefaultConfig()
        config = CoreConfig(plugin.config)
        storage = Storage(dataFolder)

        profiles = ProfileServiceImpl(storage)
        roles = RoleServiceImpl(this)
        spirits = SpiritServiceImpl(this)
        eras = EraServiceImpl(this)
        powers = PowerServiceImpl(this)
        narrator = NarratorImpl(this)
        extensions = ExtensionServiceImpl(this)
        chronicle = ChronicleImpl(this, File(dataFolder, "chronicle.yml"))

        loadState()
        chronicle.load()

        // Published into the same registry addons use. A story addon does
        // `Mythos.from(context)` and gets all five, with no `depends:` at all.
        AddonServices.register(RoleService::class.java, roles)
        AddonServices.register(SpiritService::class.java, spirits)
        AddonServices.register(EraService::class.java, eras)
        AddonServices.register(PowerService::class.java, powers)
        AddonServices.register(ProfileService::class.java, profiles)
        AddonServices.register(NarratorService::class.java, narrator)
        AddonServices.register(ChronicleService::class.java, chronicle)
        AddonServices.register(ExtensionService::class.java, extensions)

        display = MythosHud(this)
        plugin.server.pluginManager.registerEvents(display, plugin)
        display.start()

        plugin.server.pluginManager.registerEvents(CoreListener(this), plugin)

        plugin.commands.register(ClaimCommand(this))
        plugin.commands.register(RolesCommand(this))
        plugin.commands.register(RoleCommand(this))
        plugin.commands.register(SpiritCommand(this))
        plugin.commands.register(EraCommand(this))
        plugin.commands.register(PowerCommand(this))
        plugin.commands.register(MythosAdminCommand(this))
        plugin.commands.register(ChronicleCommand(this))

        // Essence drips to the watching dead.
        schedulers.asyncRepeating(
            config.essenceIntervalMinutes,
            config.essenceIntervalMinutes,
            TimeUnit.MINUTES,
        ) { spirits.tick() }

        // Periodic save. Async: never touch the disk from a region thread.
        schedulers.asyncRepeating(5, 5, TimeUnit.MINUTES) {
            profiles.saveAll()
            saveStateNow()
        }

        // One tick from now, every ADDON has enabled and registered its eras and roles
        // — only then can we know which age the world is in. (Addons load synchronously
        // inside onEnable, so this fires strictly after all of them.)
        schedulers.globalDelayed(1) {
            eras.bootstrap()
            Bukkit.getOnlinePlayers().forEach { player ->
                val role = roles.roleOf(player.uniqueId)
                schedulers.entity(player) {
                    if (role != null) roles.applyBody(player, role) else spirits.makeSpirit(player, "the world was remade")
                }
            }
        }

        logger.info("Engine awake: ${roles.definitions().size} roles, ${eras.eras().size} eras. Waiting on the stories.")
    }

    fun disable() {
        if (!::profiles.isInitialized) return
        profiles.saveAll()
        saveStateNow()
        chronicle.save()
    }

    // ---- state --------------------------------------------------------------

    private fun loadState() {
        val state = storage.loadState()

        eras.load(
            currentId = state.getString("era.current", "")!!,
            done = state.getStringList("era.completed"),
            past = state.getStringList("era.passed"),
        )

        val holders = HashMap<String, List<UUID>>()
        state.getConfigurationSection("roles.holders")?.getKeys(false)?.forEach { roleId ->
            holders[roleId] = state.getStringList("roles.holders.$roleId").mapNotNull { it.toUuidOrNull() }
        }
        val heirs = HashMap<UUID, UUID>()
        state.getConfigurationSection("roles.heirs")?.getKeys(false)?.forEach { holder ->
            val h = holder.toUuidOrNull() ?: return@forEach
            state.getString("roles.heirs.$holder")?.toUuidOrNull()?.let { heirs[h] = it }
        }
        roles.load(holders, state.getStringList("roles.sealed"), heirs)

        val interests = HashMap<UUID, String>()
        state.getConfigurationSection("spirits.interests")?.getKeys(false)?.forEach { uuid ->
            val u = uuid.toUuidOrNull() ?: return@forEach
            state.getString("spirits.interests.$uuid")?.let { interests[u] = it }
        }
        spirits.load(state.getStringList("spirits.queue").mapNotNull { it.toUuidOrNull() }, interests)
    }

    /** Fire-and-forget save. Safe from any region. */
    fun saveState() {
        schedulers.async { saveStateNow() }
    }

    fun saveStateNow() {
        val state = YamlConfiguration()
        state.set("era.current", eras.snapshotCurrent())
        state.set("era.completed", eras.snapshotCompleted())
        state.set("era.passed", eras.snapshotPassed())
        roles.snapshotHolders().forEach { (roleId, uuids) ->
            state.set("roles.holders.$roleId", uuids.map(UUID::toString))
        }
        state.set("roles.sealed", roles.snapshotSealed())
        roles.snapshotHeirs().forEach { (holder, heir) ->
            state.set("roles.heirs.$holder", heir.toString())
        }
        state.set("spirits.queue", spirits.snapshotQueue().map(UUID::toString))
        spirits.snapshotInterests().forEach { (uuid, roleId) ->
            state.set("spirits.interests.$uuid", roleId)
        }
        runCatching { storage.saveState(state) }
            .onFailure { logger.warning("Could not write state.yml: ${it.message}") }
    }

    private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
}
