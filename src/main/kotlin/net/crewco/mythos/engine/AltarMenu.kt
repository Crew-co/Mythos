package net.crewco.mythos.engine

import net.crewco.mythos.api.role.ClaimResult
import net.crewco.mythos.api.role.RoleDefinition
import net.crewco.mythos.api.role.RoleTier
import net.crewco.mythos.command.CommandContext.Companion.mm
import net.crewco.mythos.menu.Menu
import net.crewco.mythos.menu.MenuItem
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * The Altar of Fate — `/claim` with no arguments.
 *
 * Built on the host's menu framework, which means this file is about the *myth*, not
 * about InventoryHolders and click cancellation.
 *
 * It shows what you can take, and — greyed out, with the actual reason — what you
 * can't. The locked half is the more interesting one: "Titans are not claimed. They
 * are born, and Gaia has not borne you" teaches a player how the world works far
 * better than a wiki page would.
 */
class AltarMenu(private val core: MythosEngine) : Menu(mm("<dark_gray>The Altar of Fate"), rows = 6) {

    override fun build(viewer: Player) {
        val claimable = core.roles.claimableBy(viewer)
        val vacant = core.roles.openRoles()
        val locked = vacant.filterNot { role -> claimable.any { it.id == role.id } }
        val offered = core.spirits.pendingOffer(viewer.uniqueId)

        var slot = 0

        // What's yours.
        claimable.take(27).forEach { role ->
            val isOffer = role.id == offered
            item(slot++, MenuItem(icon(role, locked = false, reason = null, offered = isOffer)) { click ->
                when (val result = core.roles.claim(click.player, role.id)) {
                    is ClaimResult.Allow -> click.close()
                    is ClaimResult.Deny -> {
                        click.player.sendMessage(mm("<red>${result.reason}"))
                        click.refresh() // someone may have taken it a tick before you
                    }
                }
            })
        }

        // What isn't, and why.
        slot = maxOf(slot, 27)
        locked.take(18).forEach { role ->
            val reason = (core.roles.evaluate(viewer, role.id) as? ClaimResult.Deny)?.reason
            item(slot++, MenuItem(icon(role, locked = true, reason = reason, offered = false)) { click ->
                reason?.let { click.player.sendMessage(mm("<dark_gray>» <gray><i>$it")) }
            })
        }

        // The bottom row: who you are, and what you're waiting for.
        val uuid = viewer.uniqueId
        val spirit = core.spirits.isSpirit(uuid)
        val role = core.roles.roleOf(uuid)

        item(
            45,
            MenuItem(
                simple(
                    if (spirit) Material.SOUL_LANTERN else Material.NETHER_STAR,
                    if (spirit) "<gray>A spirit" else "${role?.color}${role?.displayName}",
                    listOf(
                        "<gray>Essence: <white>${core.spirits.essence(uuid)}",
                        if (spirit) "<gray>In line: <white>#${core.spirits.queuePosition(uuid) + 1}" else "<gray>You have a name.",
                    ),
                ),
            ),
        )

        if (spirit) {
            val waiting = core.spirits.interestOf(uuid)
            item(
                49,
                MenuItem(
                    simple(
                        Material.CLOCK,
                        "<gold>Wait for anything",
                        listOf(
                            "<gray>Currently waiting for: <white>${waiting ?: "any mantle at all"}",
                            "",
                            "<dark_gray><i>Click to join the queue for whatever falls next.",
                        ),
                    ),
                ) { click ->
                    core.spirits.enqueue(click.player.uniqueId, null)
                    click.player.sendMessage(mm("<gray>You wait for whatever falls vacant."))
                    click.refresh()
                },
            )
        }

        item(53, MenuItem(simple(Material.BARRIER, "<red>Close", emptyList())) { it.close() })
    }

    // ---- icons ---------------------------------------------------------------

    private fun icon(role: RoleDefinition, locked: Boolean, reason: String?, offered: Boolean): ItemStack {
        val material = if (locked) Material.GRAY_DYE else material(role.tier)
        val name = if (locked) "<dark_gray>${role.displayName}" else "${role.color}${role.displayName}"

        val lore = ArrayList<String>()
        lore += "<dark_gray>${role.tier.displayName}${if (role.domains.isEmpty()) "" else " · ${role.domains.joinToString()}"}"
        lore += ""
        role.lore.forEach { lore += "<gray><i>$it" }
        lore += ""

        val holders = core.roles.holders(role.id).size
        if (role.maxHolders > 1) lore += "<dark_gray>$holders / ${role.maxHolders} seats taken"

        when {
            offered -> {
                lore += "<gold><b>OFFERED TO YOU</b>"
                lore += "<gray>Click to accept it."
            }
            locked -> lore += "<red><i>${reason ?: "Closed to you."}"
            else -> lore += "<green>Click to take it."
        }
        return simple(material, name, lore)
    }

    private fun simple(material: Material, name: String, lore: List<String>) = ItemStack(material).apply {
        editMeta { meta ->
            meta.displayName(mm("<!i>$name"))
            meta.lore(lore.map { mm("<!i>$it") })
        }
    }

    private fun material(tier: RoleTier) = when (tier) {
        RoleTier.PRIMORDIAL -> Material.NETHER_STAR
        RoleTier.TITAN -> Material.GOLD_BLOCK
        RoleTier.OLYMPIAN -> Material.GOLDEN_APPLE
        RoleTier.CHTHONIC -> Material.WITHER_SKELETON_SKULL
        RoleTier.MONSTER -> Material.SPIDER_EYE
        RoleTier.DEMIGOD -> Material.IRON_SWORD
        RoleTier.HERO -> Material.SHIELD
        RoleTier.MORTAL -> Material.WHEAT
    }
}
