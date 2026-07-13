package net.crewco.mythos.api.power

import org.bukkit.entity.Player
import java.util.UUID

/** Handed to a power when it fires, so it can see how it was triggered. */
data class PowerContext(
    val player: Player,
    /** Args if it came from `/power <id> <args...>`, else empty. */
    val args: List<String>,
    val trigger: Trigger,
) {
    enum class Trigger { COMMAND, ITEM, PASSIVE, STORY }
}

/**
 * A divine ability. Registered by a story addon, granted by a role, fired by
 * `/power <id>` or by the addon itself.
 *
 * Folia: [use] runs on the region that owns the player. Touching *another*
 * player or a distant block from here is illegal — schedule onto their region
 * with `context.schedulers.entity(target) { ... }`.
 */
interface Power {
    val id: String
    val displayName: String
    val description: String
    val cooldownSeconds: Int

    /** Return false to refuse the use (bad args, wrong target) — no cooldown is burned. */
    fun use(ctx: PowerContext): Boolean
}

interface PowerService {
    fun register(power: Power)
    fun power(id: String): Power?

    /** Every power the player's current role grants them. */
    fun powersOf(uuid: UUID): List<Power>

    /** Checks role, checks cooldown, fires PowerUsedEvent, runs it, starts the cooldown. */
    fun use(player: Player, powerId: String, args: List<String> = emptyList()): Boolean

    /** Seconds left, or 0. */
    fun cooldown(uuid: UUID, powerId: String): Long

    /** Story addons can wipe or impose cooldowns (a boon from Hermes, a curse from Hera). */
    fun setCooldown(uuid: UUID, powerId: String, seconds: Long)
}
