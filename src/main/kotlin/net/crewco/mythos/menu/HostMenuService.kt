package net.crewco.mythos.menu

import net.crewco.mythos.MythosPlugin
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The host's GUI framework. One listener, for every addon on the server.
 *
 * The InventoryHolder is how we know an inventory is ours — no metadata keys, no
 * title-string matching, no chance of colliding with a chest a player actually owns.
 */
class HostMenuService(private val host: MythosPlugin) : MenuService, Listener {

    private class MenuHolder(val menu: Menu) : InventoryHolder {
        lateinit var backing: Inventory
        override fun getInventory(): Inventory = backing
    }

    private val open = ConcurrentHashMap<UUID, Menu>()

    override fun open(player: Player, menu: Menu) {
        // Opening an inventory is an operation on the player: their region, their thread.
        host.schedulers.entity(player) {
            menu.render(player)
            val holder = MenuHolder(menu)
            val inventory = Bukkit.createInventory(holder, menu.size, menu.title)
            holder.backing = inventory
            menu.contents().forEach { (slot, item) -> inventory.setItem(slot, item.icon) }
            open[player.uniqueId] = menu
            player.openInventory(inventory)
        }
    }

    override fun refresh(player: Player) {
        host.schedulers.entity(player) {
            val top = player.openInventory.topInventory
            val holder = top.holder as? MenuHolder ?: return@entity
            val menu = holder.menu
            menu.render(player)
            top.clear()
            menu.contents().forEach { (slot, item) -> top.setItem(slot, item.icon) }
            player.updateInventory()
        }
    }

    override fun close(player: Player) {
        host.schedulers.entity(player) { player.closeInventory() }
    }

    override fun openMenu(player: Player): Menu? = open[player.uniqueId]

    // ---- the one listener ----------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? MenuHolder ?: return
        event.isCancelled = true // menus are never a place to store things

        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory !== event.view.topInventory) return

        val item = holder.menu.itemAt(event.rawSlot) ?: return
        // We're already on the region that owns this player.
        runCatching { item.onClick(MenuClick(player, event.rawSlot, event.click, holder.menu, this)) }
            .onFailure { host.logger.warning("Menu click handler threw: ${it.message}") }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is MenuHolder) event.isCancelled = true
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        if (event.inventory.holder is MenuHolder) open.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        open.remove(event.player.uniqueId)
    }

    /**
     * An addon is being unloaded. Any menu it defined is about to become a lambda with
     * a dead classloader behind it — close them now, before someone clicks one.
     */
    fun closeAllFrom(loader: ClassLoader) {
        open.entries
            .filter { it.value.javaClass.classLoader === loader }
            .mapNotNull { Bukkit.getPlayer(it.key) }
            .forEach { close(it) }
    }

    /** Used by HostAddonContext to close the menus one particular addon opened. */
    fun closeFor(uuids: Collection<UUID>) {
        uuids.mapNotNull { Bukkit.getPlayer(it) }.forEach { close(it) }
    }
}
