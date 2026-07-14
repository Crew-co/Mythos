package net.crewco.mythos

import net.crewco.mythos.addon.AddonManager
import net.crewco.mythos.command.CommandManager
import net.crewco.mythos.commands.AddonsCommand
import net.crewco.mythos.engine.MythosEngine
import net.crewco.mythos.engine.RealmServiceImpl
import org.bukkit.generator.ChunkGenerator
import java.io.File
import net.crewco.mythos.hud.HostHudService
import net.crewco.mythos.menu.HostMenuService
import net.crewco.mythos.scheduler.Schedulers
import org.bukkit.plugin.java.JavaPlugin

/**
 * Entry point.
 *
 * Startup order matters: platform (schedulers, commands, menus, HUD) → **engine** →
 * addons LAST, so a story addon can rely on the whole myth engine being ready inside
 * its onEnable().
 *
 * Folia note: there is no main thread. For anything timed that touches the
 * world, use [schedulers], never Bukkit.getScheduler().
 */
class MythosPlugin : JavaPlugin() {

    lateinit var schedulers: Schedulers
        private set

    lateinit var commands: CommandManager
        private set

    lateinit var addons: AddonManager
        private set

    /** Chest-GUI framework, shared by every addon. */
    lateinit var menus: HostMenuService
        private set

    /** Boss bars / sidebar / action bar, shared by every addon. */
    lateinit var hud: HostHudService
        private set

    /**
     * The myth engine: roles, spirits, eras, powers, profiles.
     *
     * This is what Mythos IS. The addons are the stories it runs.
     */
    lateinit var engine: MythosEngine
        private set

    override fun onEnable() {
        schedulers = Schedulers(this)
        commands = CommandManager(this)

        // Platform services. The host is more than a classloader: it owns the GUI
        // framework and the HUD, so addons don't each hand-roll (and fight over) them.
        menus = HostMenuService(this)
        hud = HostHudService(this)
        server.pluginManager.registerEvents(menus, this)
        server.pluginManager.registerEvents(hud, this)

        // Host commands.
        commands.register(AddonsCommand(this))

        // The engine, BEFORE the addons: it publishes RoleService, SpiritService,
        // EraService, PowerService and ProfileService into the shared service registry,
        // so `Mythos.from(context)` resolves inside a story addon's onEnable().
        engine = MythosEngine(this)
        engine.enable()

        // Addons last — they register their eras, roles and powers against a fully
        // initialized engine. This also creates plugins/Mythos/addons/ on first run.
        addons = AddonManager(this)
        addons.loadAll()

        // Every addon has now declared its realms. Build the cosmos — worlds can only be
        // created during startup, and this is the last moment we're still in it.
        engine.createRealms()

        logger.info("Enabled ${pluginMeta.name} v${pluginMeta.version} (Folia: ${Schedulers.isFolia})")
    }

    /**
     * **Bukkit asks this while it is loading worlds — which happens BEFORE any plugin's onEnable, and
     * therefore long before a single addon has declared a single realm.**
     *
     * So we cannot ask the engine what a "void" is. We read `realms.yml`, which the engine wrote on the
     * previous boot for exactly this moment. First install: the realms are registered in bukkit.yml and
     * the server is told to restart. Every boot after that, this method is how they come back.
     *
     * `bukkit.yml`:
     * ```yaml
     * worlds:
     *   mythos_void:
     *     generator: Mythos:void
     * ```
     */
    override fun getDefaultWorldGenerator(worldName: String, id: String?): ChunkGenerator? {
        val realmId = id?.takeIf { it.isNotBlank() } ?: return null

        // The engine may not exist yet — this runs before onEnable. Read the cache directly.
        val cache = File(dataFolder, "realms.yml")
        if (!cache.exists()) {
            logger.warning("Bukkit wants a generator for '$worldName' (realm '$realmId') but realms.yml is missing.")
            return null
        }
        val generator = RealmServiceImpl.generatorFromCache(cache, realmId)
        if (generator == null) logger.warning("No cached generator for realm '$realmId' — is the addon that declared it still installed?")
        else logger.info("Generating world '$worldName' as realm '$realmId'.")
        return generator
    }

    override fun onDisable() {
        // Addons first, while the engine is still up and can record what they did.
        if (::addons.isInitialized) addons.unloadAll()
        if (::engine.isInitialized) engine.disable()
        logger.info("Disabled.")
    }
}
