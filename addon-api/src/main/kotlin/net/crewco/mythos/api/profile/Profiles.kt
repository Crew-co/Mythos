package net.crewco.mythos.api.profile

import java.util.UUID

/**
 * The player's whole history in the myth: what they are, what they were, what
 * they've earned, and who owes them.
 *
 * Story addons should use [flags] and [favor] instead of inventing their own
 * storage — it's persisted, per-player, and survives a `/addons reload`.
 */
interface MythosProfile {
    val uuid: UUID
    val name: String

    var roleId: String?

    /** Roles held and lost, in order. The Odyssey wants to know you were once a king. */
    val pastRoles: List<String>

    /** Spirit currency. Earned by witnessing myth, spent on claiming a mantle. */
    var essence: Int

    /** Standing with each god: favor["zeus"] = 40. Negative is hubris, and it is noticed. */
    val favor: MutableMap<String, Int>

    /** Earned epithets: "Oath-breaker", "Sacker of Cities". Shown in chat/nametag. */
    val titles: MutableSet<String>

    /**
     * Free-form per-addon state. NAMESPACE YOUR KEYS: "trojanwar.ships-burned".
     * Values must be String/Int/Long/Double/Boolean/List — it goes to YAML as-is.
     */
    val flags: MutableMap<String, Any>

    fun flag(key: String): Any?
    fun setFlag(key: String, value: Any?)
    fun hasFlag(key: String): Boolean

    fun addFavor(godRoleId: String, amount: Int)
    fun favorWith(godRoleId: String): Int
}

interface ProfileService {
    /** Never null — a profile is created on first sight of a player. */
    fun profile(uuid: UUID): MythosProfile
    fun profileByName(name: String): MythosProfile?
    fun save(uuid: UUID)
    fun saveAll()
}
