package net.crewco.mythos.addon

import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.logging.Logger

/**
 * Everything an addon is allowed to touch in the host. This is the API surface
 * you must keep stable — bump [HostApi.ADDON_API_VERSION] when you break it.
 *
 * Anything registered through these helpers is tracked per-addon and cleaned up
 * automatically when the addon is disabled/reloaded.
 */
interface AddonContext {

    /** The host plugin (use for Bukkit APIs that need a Plugin handle). */
    val plugin: Plugin

    /** This addon's own metadata. */
    val description: AddonDescription

    /** Prefixed logger, e.g. "[MyAddon] ...". */
    val logger: Logger

    /** Private folder for this addon: `plugins/<Host>/addons/<Name>/`. */
    val dataFolder: File

    /** Folia-safe schedulers (global / region / entity / async). */
    val schedulers: AddonSchedulers

    /** Register a command handler (same @Command annotations as the host). */
    fun registerCommand(handler: Any)

    /** Register a Bukkit listener; unregistered automatically on disable. */
    fun registerListener(listener: Listener)

    /** Publish a service other addons can look up by interface. */
    fun <T : Any> registerService(type: Class<T>, service: T)

    /** Look up a service published by the host or another addon. */
    fun <T : Any> service(type: Class<T>): T?

    /** Look up another loaded addon by name (null if absent/not loaded). */
    fun addon(name: String): Addon?
}

/** Convenience reified accessors. */
inline fun <reified T : Any> AddonContext.service(): T? = service(T::class.java)

inline fun <reified T : Any> AddonContext.registerService(service: T) =
    registerService(T::class.java, service)

/** Host constants shared with addons. */
object HostApi {
    /**
     * Bump this whenever the addon API changes incompatibly. Addons declare an
     * `api-version` in addon.yml; the loader refuses to load mismatches rather
     * than letting them fail later with a confusing NoSuchMethodError.
     */
    const val ADDON_API_VERSION: Int = 1
}
