package net.crewco.mythos.api.spirit

import org.bukkit.entity.Player
import java.util.UUID

/**
 * Everyone who isn't currently *somebody* is a spirit: bodiless, unkillable,
 * unable to touch the world, drifting through it and waiting for a mantle to
 * fall vacant.
 *
 * Spirits are not a punishment — they're the audience *and* the succession pool.
 * They earn **essence** by witnessing history (a god dying nearby, an era turning,
 * simply being present at a myth), and essence is what buys a seat at the table
 * when one opens.
 */
interface SpiritService {

    fun isSpirit(uuid: UUID): Boolean
    fun spirits(): List<UUID>

    /** Strip a player to spirit form. Idempotent. */
    fun makeSpirit(player: Player, reason: String)

    /** Lift them back into a body — called for you by RoleService.claim/assign. */
    fun embody(player: Player)

    // ---- the queue ---------------------------------------------------------

    /** Ordered: front of the queue gets first refusal on the next open role. */
    fun queue(): List<UUID>
    fun queuePosition(uuid: UUID): Int

    /** Register interest. `roleId = null` means "any role, I'm not fussy". */
    fun enqueue(uuid: UUID, roleId: String?)
    fun dequeue(uuid: UUID)
    fun interestOf(uuid: UUID): String?

    // ---- offers ------------------------------------------------------------

    /**
     * Offer a spirit a specific role for [seconds]. They accept with `/claim`;
     * on timeout or refusal it rolls to the next spirit in line. Core does this
     * automatically whenever a role opens — call it yourself only if a *story*
     * hand-picks someone (Gaia choosing which spirit is born a Titan).
     */
    fun offer(uuid: UUID, roleId: String, seconds: Int = 60)
    fun pendingOffer(uuid: UUID): String?
    fun revokeOffer(uuid: UUID)

    // ---- essence -----------------------------------------------------------

    fun essence(uuid: UUID): Int
    fun grantEssence(uuid: UUID, amount: Int, reason: String)
    fun spendEssence(uuid: UUID, amount: Int): Boolean
}
