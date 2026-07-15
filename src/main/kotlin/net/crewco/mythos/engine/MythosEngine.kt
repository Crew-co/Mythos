package net.crewco.mythos.engine

import net.crewco.mythos.MythosPlugin
import net.crewco.mythos.addon.AddonSchedulers
import net.crewco.mythos.addon.AddonServices
import net.crewco.mythos.api.dev.DevService
import net.crewco.mythos.api.era.EraService
import net.crewco.mythos.api.event.MythosResetEvent
import net.crewco.mythos.api.ext.ExtensionService
import net.crewco.mythos.api.power.PowerService
import net.crewco.mythos.api.profile.ProfileService
import net.crewco.mythos.api.realm.RealmService
import net.crewco.mythos.api.world.TerraformService
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

    /** Solo mode. See DevService — you cannot playtest a hundred-player myth with one player. */
    lateinit var dev: DevServiceImpl
        private set

    /** The cosmos: the Void, Gaia, Tartarus, Olympus. Actual worlds, with rules about who may stand in them. */
    lateinit var realms: RealmServiceImpl
        private set

    /** Undo, for the world. The flood must be able to go back down. */
    lateinit var terraform: TerraformServiceImpl
        private set

    /** Doors you can walk to. */
    lateinit var gateways: Gateways
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
        dev = DevServiceImpl(this)
        realms = RealmServiceImpl(this)
        terraform = TerraformServiceImpl(this, File(dataFolder, "scars.yml"))
        gateways = Gateways(this, File(dataFolder, "gateways.yml"))
        realms.gateways = gateways
        chronicle = ChronicleImpl(this, File(dataFolder, "chronicle.yml"))

        loadState()
        chronicle.load()
        terraform.load()
        gateways.load()
        gateways.start()

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
        AddonServices.register(DevService::class.java, dev)
        AddonServices.register(RealmService::class.java, realms)
        AddonServices.register(TerraformService::class.java, terraform)

        display = MythosHud(this)
        plugin.server.pluginManager.registerEvents(display, plugin)
        display.start()

        plugin.server.pluginManager.registerEvents(CoreListener(this), plugin)

        val realmListener = RealmListener(this)
        plugin.server.pluginManager.registerEvents(realmListener, plugin)
        realmListener.startAmbient()

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
            val starting = eras.bootstrap()

            /*
             * BUG, fixed: we used to place players in the same breath as bootstrapping the era.
             *
             * But starting an age is a TRANSITION — the prologue plays, the world holds still, and
             * `current` is not set until the interlude ends. So PlayerBecameSpiritEvent fired while
             * currentId() was still "", every story addon's "is this my era?" check said no, and the
             * spirits of the Age of Chaos were left standing in the overworld instead of the Void.
             *
             * Wait for the age to actually begin.
             */
            val settle = if (starting) config.interludeTicks + 40 else 0
            schedulers.globalDelayed(settle) {
                Bukkit.getOnlinePlayers().forEach { player ->
                    val role = roles.roleOf(player.uniqueId)
                    schedulers.entity(player) {
                        if (role != null) roles.applyBody(player, role) else spirits.makeSpirit(player, "the world was remade")
                    }
                }
            }
        }

        logger.info("Engine awake: ${roles.definitions().size} roles, ${eras.eras().size} eras. Waiting on the stories.")
    }

    /**
     * Build every realm an addon declared.
     *
     * Called by MythosPlugin AFTER addons.loadAll() and still inside onEnable — the only
     * window in which creating a world is legal on Folia, and the only moment at which we
     * know the full list.
     */
    fun createRealms() {
        realms.createAll()
    }

    fun disable() {
        // enable() can fail halfway (a bad config, a corrupt state.yml). Don't compound
        // it by throwing UninitializedPropertyAccessException on the way out.
        if (!::profiles.isInitialized) return
        profiles.saveAll()
        saveStateNow()
        if (::chronicle.isInitialized) chronicle.save()
        if (::terraform.isInitialized) terraform.save()
        if (::gateways.isInitialized) gateways.save()
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
        dev.load(state.getBoolean("dev", false))
        if (dev.enabled) logger.warning("SOLO MODE IS ON. Gates, costs and cooldowns are off for admins.")
    }

    // ---- the reset ----------------------------------------------------------

    /**
     * Back to before the world.
     *
     * The engine can only wipe what the engine owns. It has no idea that Titanomachy
     * keeps a kill tally, or that OlympianOrder remembers where it put Olympus — so it
     * fires MythosResetEvent and the addons clean up after themselves. Anything that
     * doesn't listen keeps its state, and that's on it.
     */
    fun resetWorld(by: String) {
        schedulers.global {
            eras.resetAll()
            roles.resetAll()
            spirits.resetAll()
            powers.clearCooldowns(null)
            profiles.wipeAll()
            chronicle.clear()

            // Put the world back. A reset that leaves the last flood standing is not a reset.
            terraform.scars().forEach { terraform.heal(it) }
            gateways.clear()

            runCatching { storage.deleteState() }

            Bukkit.getPluginManager().callEvent(MythosResetEvent(MythosResetEvent.Scope.WORLD, null, by))
            logger.warning("WORLD RESET by $by — everything is gone.")

            // And then the world begins again, from whatever chapter is first.
            schedulers.globalDelayed(20) {
                eras.bootstrap()
                Bukkit.getOnlinePlayers().forEach { player ->
                    schedulers.entity(player) { spirits.makeSpirit(player, "the world was unmade and started over") }
                }
            }
        }
    }

    /** The story only: eras, objectives, mantles. Players keep their essence and their epithets. */
    fun resetStory(by: String) {
        schedulers.global {
            eras.resetAll()
            roles.resetAll()
            spirits.resetAll()
            powers.clearCooldowns(null)
            profiles.clearAllFlags() // swallowed, imprisoned, hidden — none of it happened
            profiles.saveAll()
            terraform.scars().forEach { terraform.heal(it) }
            gateways.clear()

            Bukkit.getPluginManager().callEvent(MythosResetEvent(MythosResetEvent.Scope.STORY, null, by))
            logger.warning("STORY RESET by $by — the ages have not happened.")

            schedulers.globalDelayed(20) {
                eras.bootstrap()
                Bukkit.getOnlinePlayers().forEach { player ->
                    schedulers.entity(player) { spirits.makeSpirit(player, "the story was rewound") }
                }
            }
        }
    }

    /** One player, back to a nameless spirit. */
    fun resetPlayer(uuid: UUID, by: String) {
        schedulers.global {
            roles.release(uuid, "unmade by $by")
            powers.clearCooldowns(uuid)
            profiles.wipe(uuid)
            spirits.dequeue(uuid)
            Bukkit.getPluginManager().callEvent(MythosResetEvent(MythosResetEvent.Scope.PLAYER, uuid, by))

            Bukkit.getPlayer(uuid)?.let { player ->
                schedulers.entity(player) { spirits.makeSpirit(player, "you have been unwritten") }
            }
            saveState()
        }
    }

    /** Fire-and-forget save. Safe from any region. */
    fun saveState() {
        schedulers.async { saveStateNow() }
    }

    fun saveStateNow() {
        val state = YamlConfiguration()
        state.set("dev", dev.enabled)
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
