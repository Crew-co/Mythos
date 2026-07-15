package net.crewco.mythos.engine

import io.papermc.paper.event.player.AsyncChatEvent
import net.crewco.mythos.api.event.DivineDeathEvent
import net.crewco.mythos.api.role.RoleTier
import net.crewco.mythos.command.CommandContext.Companion.mm
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the rules of the world are enforced.
 *
 * The interesting one is [onLethalDamage]: a god cannot be killed by something
 * beneath it, so core pre-cancels the blow — but it fires [DivineDeathEvent] first
 * and lets a story addon *un*-cancel it. That's how "only the adamantine sickle can
 * unmake Uranus" lives in the Creation addon instead of being hard-coded here, and
 * it's the same hook the Trojan War will use for Achilles' heel.
 */
class CoreListener(private val core: MythosEngine) : Listener {

    /** Gods who took a fatal blow that was allowed to land. */
    private val unmaking: MutableSet<UUID> = Collections.newSetFromMap(ConcurrentHashMap())

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val profile = core.profiles.impl(player.uniqueId)
        profile.name = player.name

        val role = core.roles.roleOf(player.uniqueId)
        core.schedulers.entity(player) {
            if (role != null && core.roles.holders(role.id).contains(player.uniqueId)) {
                core.roles.applyBody(player, role)
                player.sendMessage(mm("<gray>You wake as ${role.color}${role.displayName}<gray>."))
                return@entity
            }

            // Roleless. On a big server there is usually somewhere to put them.
            val fallback = core.config.defaultRole
            if (fallback.isNotEmpty() && core.roles.isOpen(fallback)) {
                core.schedulers.global { core.roles.assign(player.uniqueId, fallback, "born into the world") }
                return@entity
            }
            core.spirits.markSpirit(player.uniqueId)
            core.spirits.makeSpirit(player, "you are not yet anyone")
        }
        core.schedulers.async { core.profiles.save(player.uniqueId) }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        core.profiles.profile(uuid).setFlag("core.last-seen", System.currentTimeMillis())
        core.spirits.revokeOffer(uuid)
        core.schedulers.async { core.profiles.save(uuid) }
    }

    /** Spirits are bodiless: they cannot be struck, and they cannot strike. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onSpiritDamage(event: EntityDamageByEntityEvent) {
        val attacker = resolveAttacker(event.damager)
        if (attacker != null && core.spirits.isSpirit(attacker.uniqueId)) {
            event.isCancelled = true
            attacker.sendMessage(mm("<gray>Your hand passes through the world."))
            return
        }
        val victim = event.entity as? Player ?: return
        if (core.spirits.isSpirit(victim.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onLethalDamage(event: EntityDamageEvent) {
        val victim = event.entity as? Player ?: return
        val role = core.roles.roleOf(victim.uniqueId) ?: return
        if (event.finalDamage < victim.health) return // not fatal — let it hurt

        val killer = (event as? EntityDamageByEntityEvent)?.let { resolveAttacker(it.damager) }
        val killerRole = killer?.let { core.roles.roleOf(it.uniqueId) }

        // By default: only something of sufficient stature may unmake a god, and
        // the world itself (lava, a long fall) never can.
        val permitted = when {
            killerRole != null -> role.tier.killableBy(killerRole.tier)
            killer != null -> role.tier.killableBy(RoleTier.MORTAL)
            else -> role.tier.heartsBonus == 0 // only true mortals die to the world
        }

        val divineDeath = DivineDeathEvent(victim, role, killer, killerRole)
        divineDeath.isCancelled = !permitted
        divineDeath.unmakes = core.config.releaseOnDivineDeath
        victim.server.pluginManager.callEvent(divineDeath)

        if (divineDeath.isCancelled) {
            event.isCancelled = true
            victim.health = maxOf(1.0, victim.health - 1.0)
            victim.sendMessage(mm("<gray>The blow lands, and does not matter. <dark_gray><i>You are ${role.displayName}."))
            killer?.sendMessage(mm("<gray>You cannot unmake ${role.color}${role.displayName}<gray>. Not with that."))
            return
        }
        if (divineDeath.unmakes) unmaking += victim.uniqueId
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        val uuid = event.player.uniqueId
        if (!unmaking.remove(uuid)) return
        val role = core.roles.roleOf(uuid) ?: return
        core.schedulers.global {
            core.roles.release(uuid, "unmade — ${role.displayName} falls")
        }
    }

    @EventHandler
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (core.spirits.isSpirit(player.uniqueId)) event.isCancelled = true
    }

    @EventHandler
    fun onDrop(event: PlayerDropItemEvent) {
        if (core.spirits.isSpirit(event.player.uniqueId)) event.isCancelled = true
    }

    /** Chat carries your name in the myth, or marks you as one of the waiting dead. */
    @EventHandler
    fun onChat(event: AsyncChatEvent) {
        if (!core.config.prefixNames) return
        val role = core.roles.roleOf(event.player.uniqueId)
        val prefix = if (role != null) "${role.color}${role.displayName}<dark_gray> · <white>" else "<dark_gray>+ <gray>"
        event.renderer { source, _, message, _ ->
            mm("$prefix${source.name}<dark_gray>: <white>").append(message)
        }
    }

    private fun resolveAttacker(damager: org.bukkit.entity.Entity): Player? = when (damager) {
        is Player -> damager
        is Projectile -> damager.shooter as? Player
        else -> null
    }
}
