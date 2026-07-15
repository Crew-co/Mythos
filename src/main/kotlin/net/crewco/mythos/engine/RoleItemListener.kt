package net.crewco.mythos.engine

import net.crewco.mythos.api.event.RoleReleasedEvent
import net.crewco.mythos.api.role.RoleItems
import net.crewco.mythos.command.CommandContext.Companion.mm
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * **A role-bound item belongs to the mantle, and stays with it.**
 *
 * Two jobs. It **strips** bound items the moment a role falls vacant (abdicate, death, deposition,
 * admin release) — so an abdicated Gaia can't keep the Seed — and it **pins** them while the role is
 * held: they can't be dropped, shift-clicked into a chest, dragged out, or looted off a corpse.
 * Together that makes a bound item as good as soulbound to whoever wears the mantle, and gone the
 * instant they don't. Transferable artifacts (the Adamantine Sickle) are simply never bound, and
 * this listener never touches them.
 */
class RoleItemListener(private val engine: MythosEngine) : Listener {

    /** Items pulled out of a death so they don't scatter, held until the player respawns. */
    private val deathKept = ConcurrentHashMap<UUID, List<ItemStack>>()

    // ---- strip on release ---------------------------------------------------

    @EventHandler
    fun onReleased(event: RoleReleasedEvent) {
        val player = event.player ?: return
        engine.schedulers.entity(player) { strip(player, only = event.role.id) }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        engine.schedulers.entityDelayed(player, 20) {
            strip(player, keep = engine.roles.roleOf(player.uniqueId)?.id)
        }
    }

    // ---- pin while held -----------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (!ownsBound(event.player, event.itemDrop.itemStack)) return
        event.isCancelled = true
        event.player.sendMessage(mm("<gray><i>It will not leave your hand."))
    }

    @EventHandler(ignoreCancelled = true)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val containerOpen = event.view.topInventory.type != InventoryType.CRAFTING

        // Drop via click (Q, or click-outside).
        if (event.action.name.startsWith("DROP") && ownsBound(player, event.currentItem)) { event.isCancelled = true; return }
        // Shift-click into an open container.
        if (event.isShiftClick && containerOpen && ownsBound(player, event.currentItem)) { event.isCancelled = true; return }
        // Placing the bound item (on the cursor) into a container slot.
        if (containerOpen && event.clickedInventory == event.view.topInventory && ownsBound(player, event.cursor)) { event.isCancelled = true; return }
    }

    @EventHandler(ignoreCancelled = true)
    fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!ownsBound(player, event.oldCursor)) return
        val top = event.view.topInventory
        if (top.type != InventoryType.CRAFTING && event.rawSlots.any { it < top.size }) event.isCancelled = true
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        val kept = ArrayList<ItemStack>()
        event.drops.removeIf { drop ->
            if (RoleItems.isBound(engine.plugin, drop)) { kept += drop; true } else false
        }
        if (kept.isNotEmpty()) deathKept[event.entity.uniqueId] = kept
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        val kept = deathKept.remove(event.player.uniqueId) ?: return
        val player = event.player
        engine.schedulers.entityDelayed(player, 5) {
            kept.forEach { item ->
                val bound = RoleItems.boundRole(engine.plugin, item) ?: return@forEach
                // Only give it back if they still wear the mantle; if death cost them the role, it's gone.
                if (engine.roles.roleOf(player.uniqueId)?.id == bound) player.inventory.addItem(item)
            }
        }
    }

    // ---- helpers ------------------------------------------------------------

    /** True if [item] is bound to the role [player] currently holds. */
    private fun ownsBound(player: Player, item: ItemStack?): Boolean {
        val bound = RoleItems.boundRole(engine.plugin, item) ?: return false
        return engine.roles.roleOf(player.uniqueId)?.id == bound
    }

    private fun strip(player: Player, only: String? = null, keep: String? = null) {
        val inv = player.inventory
        var removed = 0
        for (i in 0 until inv.size) {
            val item = inv.getItem(i) ?: continue
            val bound = RoleItems.boundRole(engine.plugin, item) ?: continue
            val remove = if (only != null) bound == only else bound != keep
            if (remove) { inv.setItem(i, null); removed++ }
        }
        if (removed > 0) player.updateInventory()
    }
}
