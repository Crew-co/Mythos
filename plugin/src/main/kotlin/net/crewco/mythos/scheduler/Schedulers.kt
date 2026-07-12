package net.crewco.mythos.scheduler

import net.crewco.mythos.addon.AddonSchedulers
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.util.concurrent.TimeUnit

/**
 * Folia has NO main thread. Each region ticks on its own thread, so you cannot
 * use `Bukkit.getScheduler()` for anything touching world state. Schedule onto
 * whatever owns the thing you want to modify:
 *
 *  - [global] → world-wide state (time, weather, your own shared data)
 *  - [region] → blocks/world at a Location
 *  - [entity] → a specific entity or player
 *  - [async]  → I/O; never touch world state here
 *
 * This implements [AddonSchedulers], which is what addons are handed.
 *
 * Ticks: 20 = 1 second. Async helpers take a real duration instead.
 */
class Schedulers(private val plugin: Plugin) : AddonSchedulers {

    // ---- global region ------------------------------------------------------

    override fun global(task: () -> Unit): ScheduledTask =
        Bukkit.getGlobalRegionScheduler().run(plugin) { task() }

    override fun globalDelayed(delayTicks: Long, task: () -> Unit): ScheduledTask =
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { task() }, delayTicks)

    override fun globalRepeating(
        initialDelayTicks: Long,
        periodTicks: Long,
        task: (ScheduledTask) -> Unit,
    ): ScheduledTask =
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { task(it) }, initialDelayTicks, periodTicks)

    // ---- location / region --------------------------------------------------

    override fun region(location: Location, task: () -> Unit): ScheduledTask =
        Bukkit.getRegionScheduler().run(plugin, location) { task() }

    override fun regionDelayed(location: Location, delayTicks: Long, task: () -> Unit): ScheduledTask =
        Bukkit.getRegionScheduler().runDelayed(plugin, location, { task() }, delayTicks)

    override fun regionRepeating(
        location: Location,
        initialDelayTicks: Long,
        periodTicks: Long,
        task: (ScheduledTask) -> Unit,
    ): ScheduledTask =
        Bukkit.getRegionScheduler()
            .runAtFixedRate(plugin, location, { task(it) }, initialDelayTicks, periodTicks)

    // ---- entity -------------------------------------------------------------
    // `retired` fires if the entity is gone (e.g. player logged out) first.

    override fun entity(entity: Entity, retired: (() -> Unit)?, task: () -> Unit): ScheduledTask? =
        entity.scheduler.run(plugin, { task() }, retired?.let { Runnable(it) })

    override fun entityDelayed(
        entity: Entity,
        delayTicks: Long,
        retired: (() -> Unit)?,
        task: () -> Unit,
    ): ScheduledTask? =
        entity.scheduler.runDelayed(plugin, { task() }, retired?.let { Runnable(it) }, delayTicks)

    override fun entityRepeating(
        entity: Entity,
        initialDelayTicks: Long,
        periodTicks: Long,
        retired: (() -> Unit)?,
        task: (ScheduledTask) -> Unit,
    ): ScheduledTask? =
        entity.scheduler.runAtFixedRate(
            plugin,
            { task(it) },
            retired?.let { Runnable(it) },
            initialDelayTicks,
            periodTicks,
        )

    // ---- async --------------------------------------------------------------

    override fun async(task: () -> Unit): ScheduledTask =
        Bukkit.getAsyncScheduler().runNow(plugin) { task() }

    override fun asyncDelayed(delay: Long, unit: TimeUnit, task: () -> Unit): ScheduledTask =
        Bukkit.getAsyncScheduler().runDelayed(plugin, { task() }, delay, unit)

    override fun asyncRepeating(
        initialDelay: Long,
        period: Long,
        unit: TimeUnit,
        task: (ScheduledTask) -> Unit,
    ): ScheduledTask =
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, { task(it) }, initialDelay, period, unit)

    // ---- routing / ownership ------------------------------------------------

    /** Run on the region owning [sender] (player's region, or global for console). */
    fun forSender(sender: CommandSender, task: () -> Unit) {
        when (sender) {
            is Entity -> entity(sender) { task() }
            else -> global { task() }
        }
    }

    fun ownsRegion(location: Location): Boolean = Bukkit.isOwnedByCurrentRegion(location)

    fun ownsEntity(entity: Entity): Boolean = Bukkit.isOwnedByCurrentRegion(entity)

    companion object {
        /** True if running on Folia (vs plain Paper). */
        val isFolia: Boolean by lazy {
            runCatching { Class.forName("io.papermc.paper.threadedregions.RegionizedServer") }.isSuccess
        }
    }
}
