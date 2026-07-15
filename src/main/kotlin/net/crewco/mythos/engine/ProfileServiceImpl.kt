package net.crewco.mythos.engine

import net.crewco.mythos.api.profile.MythosProfile
import net.crewco.mythos.api.profile.ProfileService
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ProfileImpl(
    override val uuid: UUID,
    override var name: String,
    yaml: YamlConfiguration,
) : MythosProfile {

    override var roleId: String? = yaml.getString("role")
    override var essence: Int = yaml.getInt("essence", 0)

    private val past = ArrayList(yaml.getStringList("past-roles"))
    override val pastRoles: List<String> get() = past

    override val favor: MutableMap<String, Int> = ConcurrentHashMap<String, Int>().apply {
        yaml.getConfigurationSection("favor")?.getKeys(false)?.forEach { put(it, yaml.getInt("favor.$it")) }
    }

    override val titles: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>()).apply {
            addAll(yaml.getStringList("titles"))
        }

    override val flags: MutableMap<String, Any> = ConcurrentHashMap<String, Any>().apply {
        yaml.getConfigurationSection("flags")?.getKeys(false)?.forEach { key ->
            yaml.get("flags.$key")?.let { put(key, it) }
        }
    }

    internal fun recordPastRole(roleId: String) {
        past += roleId
    }

    override fun flag(key: String): Any? = flags[key]

    override fun setFlag(key: String, value: Any?) {
        if (value == null) flags.remove(key) else flags[key] = value
    }

    override fun hasFlag(key: String) = flags.containsKey(key)

    override fun addFavor(godRoleId: String, amount: Int) {
        favor.merge(godRoleId, amount, Int::plus)
    }

    override fun favorWith(godRoleId: String): Int = favor[godRoleId] ?: 0

    fun toYaml(): YamlConfiguration = YamlConfiguration().apply {
        set("name", name)
        set("role", roleId)
        set("essence", essence)
        set("past-roles", past)
        set("titles", titles.toList())
        favor.forEach { (k, v) -> set("favor.$k", v) }
        flags.forEach { (k, v) -> set("flags.$k", v) }
    }
}

class ProfileServiceImpl(private val storage: Storage) : ProfileService {

    private val cache = ConcurrentHashMap<UUID, ProfileImpl>()

    override fun profile(uuid: UUID): MythosProfile = cache.computeIfAbsent(uuid) {
        val yaml = storage.loadPlayer(it)
        val name = yaml.getString("name") ?: Bukkit.getOfflinePlayer(it).name ?: it.toString().take(8)
        ProfileImpl(it, name, yaml)
    }

    /** Concrete type, for core's own internals (recordPastRole, toYaml). */
    fun impl(uuid: UUID): ProfileImpl = profile(uuid) as ProfileImpl

    override fun profileByName(name: String): MythosProfile? {
        cache.values.firstOrNull { it.name.equals(name, true) }?.let { return it }
        val offline = Bukkit.getOfflinePlayer(name)
        return if (offline.hasPlayedBefore()) profile(offline.uniqueId) else null
    }

    /** Blocking write — always call this from the async scheduler. */
    override fun save(uuid: UUID) {
        val profile = cache[uuid] ?: return
        runCatching { storage.savePlayer(uuid, profile.toYaml()) }
    }

    override fun saveAll() = cache.keys.forEach(::save)

    override fun clearAllFlags() {
        // Cached players first...
        cache.values.forEach { it.flags.clear() }

        // ...then everyone who isn't online. Cheap: this only runs on an explicit reset.
        storage.knownPlayers()
            .filterNot { uuid -> cache.containsKey(uuid) }
            .forEach { uuid ->
                val yaml = storage.loadPlayer(uuid)
                yaml.set("flags", null)
                runCatching { storage.savePlayer(uuid, yaml) }
            }

        saveAll()
    }

    /** Every profile, gone: essence, favor, titles, past lives, flags. */
    fun wipeAll() {
        cache.clear()
        storage.knownPlayers().forEach { runCatching { storage.playerFile(it).delete() } }
    }

    /** One player, back to nothing. */
    fun wipe(uuid: UUID) {
        cache.remove(uuid)
        runCatching { storage.playerFile(uuid).delete() }
    }

    fun unload(uuid: UUID) {
        save(uuid)
        cache.remove(uuid)
    }
}
