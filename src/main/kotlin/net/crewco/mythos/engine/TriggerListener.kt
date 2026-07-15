package net.crewco.mythos.engine

import net.crewco.mythos.api.trigger.TriggerContext
import net.crewco.mythos.api.trigger.WorldAction
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType

/**
 * **The one listener the whole project used to have to write for itself.**
 *
 * Every story that wanted a beat to fire from the world hand-rolled its own version of this — the
 * sickle-strike in Creation, the gaze in Perseus. Now the engine listens once, in the right place,
 * on the right region, and hands the act to [TriggerServiceImpl.dispatch]. A story binds; it never
 * writes a Bukkit listener again unless it truly wants one.
 */
class TriggerListener(private val triggers: TriggerServiceImpl) : Listener {

    // ---- right-click: USE_ITEM and CLICK_BLOCK ------------------------------

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return // the event fires once per hand; take the main one
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val player = event.player
        val item = event.item
        val block = event.clickedBlock
        var consumed = false

        if (item != null) {
            val ctx = TriggerContext(WorldAction.USE_ITEM, player, block?.location ?: player.location, item = item, block = block)
            if (triggers.dispatch(WorldAction.USE_ITEM, ctx, keyed = { triggers.hasKey(item, it) })) consumed = true
        }
        if (block != null) {
            val ctx = TriggerContext(WorldAction.CLICK_BLOCK, player, block.location, item = item, block = block)
            if (triggers.dispatch(WorldAction.CLICK_BLOCK, ctx, mat = block.type)) consumed = true
        }
        if (consumed) {
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY)
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
            event.isCancelled = true
        }
    }

    // ---- right-click another entity -----------------------------------------

    @EventHandler(ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        val ctx = TriggerContext(WorldAction.INTERACT_ENTITY, player, event.rightClicked.location, target = event.rightClicked)
        if (triggers.dispatch(WorldAction.INTERACT_ENTITY, ctx)) event.isCancelled = true
    }

    // ---- melee and projectile strikes ---------------------------------------
    // NORMAL, so it runs before the engine's own lethal-damage check at HIGH: a decisive blow
    // (handler returns true) is turned into fatal damage, and core then sees a fatal blow.

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity
        when (val damager = event.damager) {
            is Player -> {
                val item = damager.inventory.itemInMainHand
                val ctx = TriggerContext(WorldAction.STRIKE_ENTITY, damager, victim.location, item = item, target = victim)
                if (triggers.dispatch(WorldAction.STRIKE_ENTITY, ctx, keyed = { triggers.hasKey(item, it) })) {
                    event.damage = 10_000.0 // the sickle knows what it is for
                }
            }
            is Projectile -> {
                val shooter = damager.shooter as? Player ?: return
                val pdc = damager.persistentDataContainer
                val ctx = TriggerContext(WorldAction.PROJECTILE_HIT, shooter, victim.location, target = victim)
                if (triggers.dispatch(WorldAction.PROJECTILE_HIT, ctx, keyed = { triggers.projectileMatches(pdc, it) })) {
                    event.damage = 10_000.0
                }
            }
        }
    }

    /** At launch, stamp the arrow with whatever projectile-key its shooter is holding — so it remembers the bow. */
    @EventHandler(ignoreCancelled = true)
    fun onLaunch(event: ProjectileLaunchEvent) {
        val shooter = event.entity.shooter as? Player ?: return
        val keys = triggers.projectileKeysHeldBy(shooter)
        if (keys.isEmpty()) return
        val pdc = event.entity.persistentDataContainer
        keys.forEach { pdc.set(it, PersistentDataType.BYTE, 1) }
    }

    // ---- place / break ------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val ctx = TriggerContext(WorldAction.PLACE_BLOCK, event.player, event.block.location, block = event.block, item = event.itemInHand)
        if (triggers.dispatch(WorldAction.PLACE_BLOCK, ctx, mat = event.block.type)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val ctx = TriggerContext(WorldAction.BREAK_BLOCK, event.player, event.block.location, block = event.block)
        if (triggers.dispatch(WorldAction.BREAK_BLOCK, ctx, mat = event.block.type)) event.isCancelled = true
    }

    // ---- drop / consume / gesture -------------------------------------------

    @EventHandler(ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        val item = event.itemDrop.itemStack
        val ctx = TriggerContext(WorldAction.DROP_ITEM, event.player, event.itemDrop.location, item = item)
        if (triggers.dispatch(WorldAction.DROP_ITEM, ctx, keyed = { triggers.hasKey(item, it) })) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        val item = event.item
        val ctx = TriggerContext(WorldAction.CONSUME_ITEM, event.player, event.player.location, item = item)
        if (triggers.dispatch(WorldAction.CONSUME_ITEM, ctx, keyed = { triggers.hasKey(item, it) })) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onSneak(event: PlayerToggleSneakEvent) {
        if (!event.isSneaking) return // fire on the press, not the release
        val ctx = TriggerContext(WorldAction.GESTURE, event.player, event.player.location)
        triggers.dispatch(WorldAction.GESTURE, ctx)
    }
}
