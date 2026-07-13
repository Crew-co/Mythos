package net.crewco.mythos.addon

import java.util.concurrent.ConcurrentHashMap

/**
 * The service registry: one map, shared by the host and every addon.
 *
 * The host publishes the engine's services into it at startup (RoleService,
 * SpiritService, EraService, PowerService, ProfileService), so an addon can call
 * `Mythos.from(context)` in its onEnable without declaring a single `depends:`.
 *
 * Addon-published services are tracked per-addon by [HostAddonContext] and removed on
 * unload — otherwise a reload would leave a service pointing at an impl loaded by a
 * dead classloader. Host services are never removed; the host outlives every addon.
 */
object AddonServices {

    private val services = ConcurrentHashMap<Class<*>, Any>()

    fun <T : Any> register(type: Class<T>, service: T) {
        services[type] = service
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(type: Class<T>): T? = services[type] as? T

    fun remove(type: Class<*>) {
        services.remove(type)
    }
}
