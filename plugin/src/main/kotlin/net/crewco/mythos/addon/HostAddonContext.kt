package net.crewco.mythos.addon

import net.crewco.mythos.MythosPlugin
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
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

    override fun registerCommand(handler: Any) {
        // Commands go through the host's CommandManager (same @Command annotations).
        host.commands.register(handler)
    }

    override fun registerListener(listener: Listener) {
        host.server.pluginManager.registerEvents(listener, host)
        listeners += listener
    }

    override fun <T : Any> registerService(type: Class<T>, service: T) {
        services[type] = service
        ownServices += type
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> service(type: Class<T>): T? = services[type] as? T

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
            ownServices.forEach { services.remove(it) }
            ownServices.clear()
        }
        // Note: commands registered into Bukkit's command map are not removed
        // here — see the README's addon reload caveat.
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

    private companion object {
        /** Shared across all addons: the host's service registry. */
        val services = ConcurrentHashMap<Class<*>, Any>()
    }
}
