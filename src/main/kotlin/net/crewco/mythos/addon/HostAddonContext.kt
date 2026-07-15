package net.crewco.mythos.addon

import net.crewco.mythos.MythosPlugin
import net.crewco.mythos.hud.HudService
import net.crewco.mythos.menu.Menu
import net.crewco.mythos.menu.MenuService
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * The host's implementation of [AddonContext], created per addon.
 *
 * It tracks everything an addon registers — listeners, services and scheduled
 * tasks — so [cleanup] can undo it all on disable/reload. Without that, a
 * reloaded addon would leave orphaned listeners and repeating tasks behind
 * (which on Folia means tasks running against a dead classloader).
 */
class HostAddonContext(
    private val host: MythosPlugin,
    private val manager: AddonManager,
    override val description: AddonDescription,
) : AddonContext {

    override val plugin: Plugin get() = host

    override val logger: Logger = Logger.getLogger("${host.logger.name}.${description.name}")

    override val dataFolder: File =
        File(manager.addonsFolder, description.name).apply { mkdirs() }

    private val listeners = Collections.synchronizedList(ArrayList<Listener>())
    private val tasks = Collections.synchronizedList(ArrayList<ScheduledTask>())

    /** Services THIS addon published — dropped on unload so a reload can't hand
     *  out a stale impl loaded by a dead classloader. */
    private val ownServices = Collections.synchronizedList(ArrayList<Class<*>>())

    override val schedulers: AddonSchedulers = TrackedSchedulers()

    override val menus: MenuService = TrackedMenus()

    override val hud: HudService = TrackedHud()

    /** Who this addon opened menus for / put things on the screen of. */
    private val menuViewers = Collections.synchronizedSet(HashSet<java.util.UUID>())
    private val barKeys = Collections.synchronizedSet(HashSet<String>())
    private val barViewers = Collections.synchronizedSet(HashSet<java.util.UUID>())
    private val sidebarViewers = Collections.synchronizedSet(HashSet<java.util.UUID>())

    override fun registerCommand(handler: Any) {
        // Commands go through the host's CommandManager (same @Command annotations).
        host.commands.register(handler)
    }

    override fun registerListener(listener: Listener) {
        host.server.pluginManager.registerEvents(listener, host)
        listeners += listener
    }

    override fun <T : Any> registerService(type: Class<T>, service: T) {
        AddonServices.register(type, service)
        ownServices += type
    }

    /** Finds the HOST's engine services (roles, spirits, eras...) and other addons' alike. */
    override fun <T : Any> service(type: Class<T>): T? = AddonServices.get(type)

    override fun addon(name: String): Addon? = manager.addon(name)

    /** Undo everything this addon registered. Called by the manager on unload. */
    fun cleanup() {
        synchronized(listeners) {
            listeners.forEach { HandlerList.unregisterAll(it) }
            listeners.clear()
        }
        synchronized(tasks) {
            tasks.forEach { runCatching { it.cancel() } }
            tasks.clear()
        }
        synchronized(ownServices) {
            ownServices.forEach { AddonServices.remove(it) }
            ownServices.clear()
        }
        // A menu whose click handler was defined by this addon is, one unload from now,
        // a lambda with a dead classloader behind it. Shut them before anyone clicks.
        synchronized(menuViewers) {
            host.menus.closeFor(menuViewers)
            menuViewers.clear()
        }
        synchronized(barViewers) {
            host.hud.clearAll(barViewers, barKeys.toList(), sidebarViewers)
            barViewers.clear()
            barKeys.clear()
            sidebarViewers.clear()
        }
        // Note: commands registered into Bukkit's command map are not removed
        // here — see the README's addon reload caveat.
    }

    /** Delegates to the host's menu service, remembering who to close on unload. */
    private inner class TrackedMenus : MenuService {
        override fun open(player: Player, menu: Menu) {
            menuViewers += player.uniqueId
            host.menus.open(player, menu)
        }

        override fun refresh(player: Player) = host.menus.refresh(player)
        override fun close(player: Player) = host.menus.close(player)
        override fun openMenu(player: Player): Menu? = host.menus.openMenu(player)
    }

    /** Delegates to the host's HUD, remembering what to tear down on unload. */
    private inner class TrackedHud : HudService {
        override fun bossBar(
            player: Player,
            key: String,
            text: Component,
            progress: Float,
            color: BossBar.Color,
            overlay: BossBar.Overlay,
        ) {
            barViewers += player.uniqueId
            barKeys += key
            host.hud.bossBar(player, key, text, progress, color, overlay)
        }

        override fun removeBossBar(player: Player, key: String) = host.hud.removeBossBar(player, key)

        override fun sidebar(player: Player, title: Component, lines: List<Component>) {
            sidebarViewers += player.uniqueId
            host.hud.sidebar(player, title, lines)
        }

        override fun clearSidebar(player: Player) = host.hud.clearSidebar(player)
        override fun actionBar(player: Player, text: Component) = host.hud.actionBar(player, text)
    }

    private fun track(task: ScheduledTask): ScheduledTask {
        tasks += task
        return task
    }

    /** Delegates to the host's Schedulers, recording tasks for cleanup. */
    private inner class TrackedSchedulers : AddonSchedulers {
        private val s get() = host.schedulers

        override fun global(task: () -> Unit) = track(s.global(task))

        override fun globalDelayed(delayTicks: Long, task: () -> Unit) =
            track(s.globalDelayed(delayTicks, task))

        override fun globalRepeating(initialDelayTicks: Long, periodTicks: Long, task: (ScheduledTask) -> Unit) =
            track(s.globalRepeating(initialDelayTicks, periodTicks, task))

        override fun region(location: Location, task: () -> Unit) = track(s.region(location, task))

        override fun regionDelayed(location: Location, delayTicks: Long, task: () -> Unit) =
            track(s.regionDelayed(location, delayTicks, task))

        override fun regionRepeating(
            location: Location,
            initialDelayTicks: Long,
            periodTicks: Long,
            task: (ScheduledTask) -> Unit,
        ) = track(s.regionRepeating(location, initialDelayTicks, periodTicks, task))

        override fun entity(entity: Entity, retired: (() -> Unit)?, task: () -> Unit) =
            s.entity(entity, retired, task)?.also { track(it) }

        override fun entityDelayed(entity: Entity, delayTicks: Long, retired: (() -> Unit)?, task: () -> Unit) =
            s.entityDelayed(entity, delayTicks, retired, task)?.also { track(it) }

        override fun entityRepeating(
            entity: Entity,
            initialDelayTicks: Long,
            periodTicks: Long,
            retired: (() -> Unit)?,
            task: (ScheduledTask) -> Unit,
        ) = s.entityRepeating(entity, initialDelayTicks, periodTicks, retired, task)?.also { track(it) }

        override fun async(task: () -> Unit) = track(s.async(task))

        override fun asyncDelayed(delay: Long, unit: TimeUnit, task: () -> Unit) =
            track(s.asyncDelayed(delay, unit, task))

        override fun asyncRepeating(initialDelay: Long, period: Long, unit: TimeUnit, task: (ScheduledTask) -> Unit) =
            track(s.asyncRepeating(initialDelay, period, unit, task))
    }

}
