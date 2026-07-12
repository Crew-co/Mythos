package net.crewco.mythos.addon

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Location
import org.bukkit.entity.Entity
import java.util.concurrent.TimeUnit

/**
 * Folia-safe scheduling, exposed to addons.
 *
 * Folia has NO main thread: each region ticks on its own thread. Addons MUST NOT
 * use `Bukkit.getScheduler()`. Instead, schedule onto whatever owns the thing
 * you're touching:
 *
 *  - [global] — world-wide state (time, weather, your own shared data)
 *  - [region] — blocks/world at a Location
 *  - [entity] — a specific entity or player
 *  - [async]  — network/disk I/O; never touch world state here
 *
 * Ticks: 20 = 1 second. The async helpers take a real duration instead.
 *
 * Tasks created here are tracked per-addon and cancelled automatically when the
 * addon is disabled or reloaded.
 */
interface AddonSchedulers {

    // ---- global region ------------------------------------------------------

    fun global(task: () -> Unit): ScheduledTask

    fun globalDelayed(delayTicks: Long, task: () -> Unit): ScheduledTask

    fun globalRepeating(initialDelayTicks: Long, periodTicks: Long, task: (ScheduledTask) -> Unit): ScheduledTask

    // ---- location / region --------------------------------------------------

    fun region(location: Location, task: () -> Unit): ScheduledTask

    fun regionDelayed(location: Location, delayTicks: Long, task: () -> Unit): ScheduledTask

    fun regionRepeating(
        location: Location,
        initialDelayTicks: Long,
        periodTicks: Long,
        task: (ScheduledTask) -> Unit,
    ): ScheduledTask

    // ---- entity -------------------------------------------------------------
    // `retired` runs instead if the entity is gone (e.g. the player logged out).

    fun entity(entity: Entity, retired: (() -> Unit)? = null, task: () -> Unit): ScheduledTask?

    fun entityDelayed(
        entity: Entity,
        delayTicks: Long,
        retired: (() -> Unit)? = null,
        task: () -> Unit,
    ): ScheduledTask?

    fun entityRepeating(
        entity: Entity,
        initialDelayTicks: Long,
        periodTicks: Long,
        retired: (() -> Unit)? = null,
        task: (ScheduledTask) -> Unit,
    ): ScheduledTask?

    // ---- async --------------------------------------------------------------

    fun async(task: () -> Unit): ScheduledTask

    fun asyncDelayed(delay: Long, unit: TimeUnit = TimeUnit.SECONDS, task: () -> Unit): ScheduledTask

    fun asyncRepeating(
        initialDelay: Long,
        period: Long,
        unit: TimeUnit = TimeUnit.SECONDS,
        task: (ScheduledTask) -> Unit,
    ): ScheduledTask
}
