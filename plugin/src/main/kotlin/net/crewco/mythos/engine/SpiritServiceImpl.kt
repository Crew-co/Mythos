package net.crewco.mythos.engine

import net.crewco.mythos.api.event.PlayerBecameSpiritEvent
import net.crewco.mythos.api.role.ClaimResult
import net.crewco.mythos.api.spirit.SpiritService
import net.crewco.mythos.command.CommandContext.Companion.mm
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The spirit world: everyone without a mantle.
 *
 * The queue is the load-bearing idea. A server of 40 players has maybe 12 gods in
 * it at any time; the other 28 aren't excluded, they're *waiting*, watching, and
 * accruing essence — and the moment Kronos is thrown into Tartarus, the queue is
 * what decides who wakes up wearing his name.
 */
class SpiritServiceImpl(private val core: MythosEngine) : SpiritService {

    private data class Offer(val roleId: String, val expiresAt: Long)

    private val spirits = CopyOnWriteArraySet<UUID>()
    private val waiting = CopyOnWriteArrayList<UUID>()           // ordered: front = first refusal
    private val interests = ConcurrentHashMap<UUID, String>()    // uuid → the role they're holding out for
    private val offers = ConcurrentHashMap<UUID, Offer>()

    override fun isSpirit(uuid: UUID) = uuid in spirits
    override fun spirits(): List<UUID> = spirits.toList()

    override fun makeSpirit(player: Player, reason: String) {
        val uuid = player.uniqueId
        spirits += uuid
        if (uuid !in waiting) waiting += uuid

        core.schedulers.entity(player) {
            player.gameMode = core.config.spiritMode
            player.isInvulnerable = true
            player.isCollidable = false
            player.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 20.0
            player.health = 20.0
            player.foodLevel = 20
            player.fireTicks = 0
            // NOTE: a spirit keeps its inventory — it just can't use it (drops and
            // pickups are cancelled in CoreListener). Wipe it here if you'd rather
            // death be total.

            if (core.config.spiritCanFly) {
                player.allowFlight = true
                player.isFlying = true
            }
            if (core.config.spiritInvisible) {
                player.addPotionEffect(
                    PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false),
                )
            }
            player.displayName(mm("<dark_gray>+ <gray>${player.name}"))
            player.playerListName(mm("<dark_gray>+ <gray>${player.name}"))

            player.showTitle(
                Title.title(
                    mm("<dark_gray><i>a spirit"),
                    mm("<gray>$reason"),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofMillis(800)),
                ),
            )
            player.sendMessage(mm("<gray>You drift, unbodied. <white>/spirit <gray>to see what you might yet become."))

            Bukkit.getPluginManager().callEvent(PlayerBecameSpiritEvent(player, reason))
        }

        // A newly-made spirit might be exactly who a vacant single-seat mantle was
        // waiting for — but only single-seat ones, and only those nobody already holds
        // an offer on. Everything else is silence.
        core.schedulers.globalDelayed(20) {
            core.roles.openRoles()
                .filter { it.maxHolders == 1 && offerHolderOf(it.id) == null }
                .forEach { offerToQueue(it.id) }
        }
    }

    override fun embody(player: Player) {
        val uuid = player.uniqueId
        spirits -= uuid
        waiting -= uuid
        interests -= uuid
        offers -= uuid
        core.schedulers.entity(player) {
            player.isInvulnerable = false
            player.isCollidable = true
            player.removePotionEffect(PotionEffectType.INVISIBILITY)
            player.isFlying = false
            player.allowFlight = false
        }
    }

    // ---- queue --------------------------------------------------------------

    override fun queue(): List<UUID> = waiting.toList()
    override fun queuePosition(uuid: UUID) = waiting.indexOf(uuid)

    override fun enqueue(uuid: UUID, roleId: String?) {
        if (uuid !in waiting) waiting += uuid
        if (roleId == null) interests -= uuid else interests[uuid] = roleId.lowercase()
    }

    override fun dequeue(uuid: UUID) {
        waiting -= uuid
        interests -= uuid
    }

    override fun interestOf(uuid: UUID) = interests[uuid]

    // ---- offers -------------------------------------------------------------

    /**
     * Walk the queue and hand this role to the first spirit who could actually
     * take it: online, interested (or not fussy), and past the role's own gates.
     */
    fun offerToQueue(roleId: String, skip: Set<UUID> = emptySet()) {
        core.schedulers.global {
            val id = roleId.lowercase()
            val definition = core.roles.definition(id) ?: return@global
            if (!core.roles.isOpen(id)) return@global
            if (offerHolderOf(id) != null) return@global // already promised to someone

            // A many-seated role (the sworn armies, the mortals of Argos) is never
            // "offered" — there's no scarcity to arbitrate. Offers exist for the ONE
            // seat everybody wants.
            if (!core.config.queuePriority || definition.maxHolders > 1) {
                core.roles.announceOpenRoles()
                return@global
            }

            val candidate = waiting.firstOrNull { uuid ->
                uuid !in skip &&
                    !offers.contains(uuid) &&
                    (interests[uuid] == null || interests[uuid] == id) &&
                    Bukkit.getPlayer(uuid) != null &&
                    passesGates(Bukkit.getPlayer(uuid)!!, definition.id)
            }

            if (candidate == null) {
                // Nobody in the queue may take it. That is a perfectly normal state —
                // no Titan is ever claimable, because Titans are BORN. Say nothing;
                // the throttled announcement handles anything genuinely up for grabs.
                return@global
            }
            offer(candidate, id, core.config.offerSeconds)
        }
    }

    /**
     * Could this spirit take this role if we offered it to them?
     *
     * One source of truth: RoleService runs the same gates it will run when they type
     * `/claim`. Offering a mantle and then refusing it is the bug we're fixing.
     */
    private fun passesGates(player: Player, roleId: String): Boolean =
        core.roles.evaluate(player, roleId) is ClaimResult.Allow

    override fun offer(uuid: UUID, roleId: String, seconds: Int) {
        val id = roleId.lowercase()
        val definition = core.roles.definition(id) ?: return
        offers[uuid] = Offer(id, System.currentTimeMillis() + seconds * 1000L)

        Bukkit.getPlayer(uuid)?.let { player ->
            core.schedulers.entity(player) {
                player.showTitle(
                    Title.title(
                        mm("${definition.color}${definition.displayName}"),
                        mm("<gray>offered to you — <white>/claim ${definition.id}"),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(4), Duration.ofMillis(800)),
                    ),
                )
                player.sendMessage(mm(""))
                player.sendMessage(mm("<dark_gray>» ${definition.color}<b>${definition.displayName}</b> <gray>has no wearer, and you are first in line."))
                definition.lore.forEach { player.sendMessage(mm("<dark_gray>| <gray><i>$it")) }
                player.sendMessage(mm("<white>  /claim ${definition.id} <gray>to accept · <white>/spirit decline <gray>to pass it on <dark_gray>(${seconds}s)"))
                player.sendMessage(mm(""))
            }
        }

        // Roll it onward if they sit on their hands.
        core.schedulers.globalDelayed(seconds * 20L) {
            val current = offers[uuid]
            if (current != null && current.roleId == id && System.currentTimeMillis() >= current.expiresAt) {
                offers -= uuid
                Bukkit.getPlayer(uuid)?.sendMessage(mm("<gray>The moment passed. ${definition.displayName} looks elsewhere."))
                // They had their chance; send them to the back of the line.
                if (waiting.remove(uuid)) waiting += uuid
                offerToQueue(id, skip = setOf(uuid))
            }
        }
    }

    override fun pendingOffer(uuid: UUID): String? = offers[uuid]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.roleId

    override fun revokeOffer(uuid: UUID) {
        offers -= uuid
    }

    /** A sealed role must not still be dangling in front of someone. */
    fun revokeOffersFor(roleId: String) {
        val id = roleId.lowercase()
        offers.entries.removeIf { it.value.roleId == id }
    }

    /** Who, if anyone, is currently being offered this role. */
    fun offerHolderOf(roleId: String): UUID? {
        val id = roleId.lowercase()
        val now = System.currentTimeMillis()
        return offers.entries.firstOrNull { it.value.roleId == id && it.value.expiresAt > now }?.key
    }

    /** `/spirit decline` — pass it to whoever's next. */
    fun decline(uuid: UUID) {
        val offer = offers.remove(uuid) ?: return
        if (waiting.remove(uuid)) waiting += uuid
        offerToQueue(offer.roleId, skip = setOf(uuid))
    }

    // ---- essence ------------------------------------------------------------

    override fun essence(uuid: UUID) = core.profiles.profile(uuid).essence

    override fun grantEssence(uuid: UUID, amount: Int, reason: String) {
        if (amount <= 0) return
        val profile = core.profiles.profile(uuid)
        profile.essence += amount
        Bukkit.getPlayer(uuid)?.sendMessage(mm("<dark_gray>+ <gray>$amount essence <dark_gray>($reason)"))
        core.schedulers.async { core.profiles.save(uuid) }
    }

    override fun spendEssence(uuid: UUID, amount: Int): Boolean {
        val profile = core.profiles.profile(uuid)
        if (profile.essence < amount) return false
        profile.essence -= amount
        core.schedulers.async { core.profiles.save(uuid) }
        return true
    }

    fun grantEssenceToAll(amount: Int, reason: String) {
        spirits.forEach { grantEssence(it, amount, reason) }
    }

    /** The slow drip: essence for haunting the world. Started by the addon. */
    fun tick() {
        spirits.filter { Bukkit.getPlayer(it) != null }
            .forEach { grantEssence(it, core.config.essencePerInterval, "watching") }
    }

    internal fun load(savedQueue: List<UUID>, savedInterests: Map<UUID, String>) {
        waiting += savedQueue.filter { it !in waiting }
        interests += savedInterests
    }

    internal fun snapshotQueue() = waiting.toList()
    internal fun snapshotInterests() = interests.toMap()
    internal fun markSpirit(uuid: UUID) { spirits += uuid }
}
