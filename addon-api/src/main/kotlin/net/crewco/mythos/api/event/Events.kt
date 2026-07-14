package net.crewco.mythos.api.event

import net.crewco.mythos.api.era.EraDefinition
import net.crewco.mythos.api.role.RoleDefinition
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * The seams of the story. Core fires these; story addons listen to them, and
 * that's how a myth written months later can react to a myth written today
 * without either addon importing the other.
 *
 * They live in addon-api on purpose: every addon must see the SAME class object,
 * or Bukkit's HandlerList won't match. (Never shade this jar into an addon.)
 *
 * Folia: these are fired from the region that owns the player/world involved, so
 * a handler may touch that player directly — but must schedule to reach anyone else.
 */

/** A player is about to take up a mantle. Cancel to forbid it. */
class RoleClaimEvent(
    val player: Player,
    val role: RoleDefinition,
    /** True if this came from an admin `/mythos assign`, bypassing the gates. */
    val forced: Boolean,
) : Event(), Cancellable {
    private var cancelled = false
    var denyReason: String = "Something unseen forbids it."

    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    override fun getHandlers() = HANDLERS

    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList() = HANDLERS
    }
}

/** Done — they are Gaia now. Grant items, set spawn, announce, start their story. */
class RoleClaimedEvent(val player: Player, val role: RoleDefinition) : Event() {
    override fun getHandlers() = HANDLERS
    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList() = HANDLERS
    }
}

/** A mantle has fallen vacant: death, deposition, or a long absence. */
class RoleReleasedEvent(
    val uuid: UUID,
    val role: RoleDefinition,
    val reason: String,
    /** Null if they were offline when it happened. */
    val player: Player?,
) : Event() {
    override fun getHandlers() = HANDLERS
    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList() = HANDLERS
    }
}

/** A player has been dissolved into the spirit world. */
class PlayerBecameSpiritEvent(val player: Player, val reason: String) : Event() {
    override fun getHandlers() = HANDLERS
    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList() = HANDLERS
    }
}

/** A beat of the current era has been struck. */
class ObjectiveCompletedEvent(
    val eraId: String,
    val objectiveId: String,
    val reason: String,
) : Event() {
    override fun getHandlers() = HANDLERS
    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList() = HANDLERS
    }
}

/**
 * The world turns. `from` is null at first boot.
 *
 * This is the hook every downstream addon waits on: the Titanomachy addon does
 * nothing at all until it sees `to.id == "titanomachy"`.
 */
class EraAdvancedEvent(
    val from: EraDefinition?,
    val to: EraDefinition,
    val reason: String,
) : Event() {
    override fun getHandlers() = HANDLERS
    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList() = HANDLERS
    }
}

/**
 * The age has turned, and this player's story is over — they're about to be returned
 * to the spirit world.
 *
 * **Cancel it to keep them on stage.** This is the hook for a myth that inherits a
 * character: the Odyssey cancels the retirement of Odysseus when the Iliad ends, and
 * he walks out of one addon's story into another's without either one importing the
 * other.
 */
class RoleRetiringEvent(
    val uuid: UUID,
    val role: RoleDefinition,
    val fromEra: String?,
    val toEra: String,
    /** Null if they were offline when the world moved on. */
    val player: Player?,
) : Event(), Cancellable {
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    override fun getHandlers() = HANDLERS

    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList() = HANDLERS
    }
}

/**
 * **The engine is about to put a dead player back in a body.**
 *
 * This is the `claiming.default-role` rule: a mortal dies, a mortal is born, five seconds later.
 * It exists so a hundred-player server doesn't turn into a hundred ghosts.
 *
 * **Cancel it if your story has opinions about death.** ChthonicRealm does: you don't come back
 * until you have crossed the river, and the river has a man on it, and he charges.
 */
class PlayerReincarnatingEvent(
    val uuid: UUID,
    /** The role they're about to be born into — the configured default. */
    val roleId: String,
    val player: Player?,
) : Event(), Cancellable {
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    override fun getHandlers() = HANDLERS

    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList() = HANDLERS
    }
}

/** A power is about to fire. Cancel it to have a rival god smother it. */
class PowerUseEvent(
    val player: Player,
    val powerId: String,
    val args: List<String>,
) : Event(), Cancellable {
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    override fun getHandlers() = HANDLERS

    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList() = HANDLERS
    }
}

/**
 * A god has been struck down by something that could actually kill it (see
 * RoleTier.killableBy). Cancel to let them survive — that's how "only the sickle
 * can unmake Uranus" is enforced by the Creation addon rather than by core.
 */
class DivineDeathEvent(
    val victim: Player,
    val victimRole: RoleDefinition,
    val killer: Player?,
    val killerRole: RoleDefinition?,
) : Event(), Cancellable {
    private var cancelled = false
    /** If true, the victim loses the role permanently instead of just respawning. */
    var unmakes: Boolean = true

    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    override fun getHandlers() = HANDLERS

    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList() = HANDLERS
    }
}
