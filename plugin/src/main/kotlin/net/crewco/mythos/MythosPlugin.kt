package net.crewco.mythos

import net.crewco.mythos.addon.AddonManager
import net.crewco.mythos.command.CommandManager
import net.crewco.mythos.commands.AddonsCommand
import net.crewco.mythos.engine.MythosEngine
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

        logger.info("Enabled ${pluginMeta.name} v${pluginMeta.version} (Folia: ${Schedulers.isFolia})")
    }

    override fun onDisable() {
        // Addons first, while the engine is still up and can record what they did.
        if (::addons.isInitialized) addons.unloadAll()
        if (::engine.isInitialized) engine.disable()
        logger.info("Disabled.")
    }
}
