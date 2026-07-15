package net.crewco.mythos.menu

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import java.util.concurrent.ConcurrentHashMap

/**
 * A chest-GUI, provided by the HOST so every addon gets the same one.
 *
 * The host owns the click listener, the inventory holder, and the cleanup, which
 * matters more than it sounds: a menu whose click handler was loaded by an addon
 * that has since been unloaded is a live grenade — the class is gone, the lambda
 * captures a dead world. The host closes any menu belonging to an addon when that
 * addon unloads.
 *
 * Folia: [build] and every click handler run on the region that owns the viewer, so
 * touching *that* player is safe. Touching anyone else is not — schedule for them.
 */
abstract class Menu(val title: Component, val rows: Int) {

    init {
        require(rows in 1..6) { "A chest menu has 1..6 rows, not $rows" }
    }

    val size: Int get() = rows * 9

    private val items = ConcurrentHashMap<Int, MenuItem>()

    /**
     * Lay out the menu for this viewer. Called on the viewer's region thread, before
     * the inventory is shown, and again on every [MenuClick.refresh].
     *
     * Build it fresh each time — that's what makes a live menu (an altar that greys
     * out a role the moment someone else claims it) trivial instead of fiddly.
     */
    protected abstract fun build(viewer: Player)

    /** Place an item. Slots are 0-based, left to right, top to bottom. */
    protected fun item(slot: Int, item: MenuItem) {
        if (slot in 0 until size) items[slot] = item
    }

    /** A decoration with no click behaviour. */
    protected fun item(slot: Int, icon: ItemStack) = item(slot, MenuItem(icon))

    fun itemAt(slot: Int): MenuItem? = items[slot]

    fun contents(): Map<Int, MenuItem> = items.toMap()

    /** Host-internal: wipe and re-lay-out. */
    fun render(viewer: Player) {
        items.clear()
        build(viewer)
    }
}

/** One clickable thing. */
class MenuItem(
    val icon: ItemStack,
    /** Runs on the viewer's region thread. Default: do nothing (a decoration). */
    val onClick: (MenuClick) -> Unit = {},
)

/** Handed to a click handler. */
class MenuClick(
    val player: Player,
    val slot: Int,
    val type: ClickType,
    val menu: Menu,
    private val menus: MenuService,
) {
    /** Re-run [Menu.build] and repaint, without closing and reopening (no flicker). */
    fun refresh() = menus.refresh(player)

    fun close() = menus.close(player)
}

/** `context.menus` — the host's GUI service. */
interface MenuService {
    fun open(player: Player, menu: Menu)

    /** Rebuild and repaint whatever they currently have open. No-op if it isn't a Menu. */
    fun refresh(player: Player)

    fun close(player: Player)

    /** The Menu they're looking at, or null. */
    fun openMenu(player: Player): Menu?
}
