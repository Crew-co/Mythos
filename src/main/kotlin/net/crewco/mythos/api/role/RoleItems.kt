package net.crewco.mythos.api.role

import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/**
 * **An item that belongs to a mantle, not a person.** `RoleItems`
 *
 * Gaia's Seed of Earth is not Steve's — it is *Gaia's*. When Steve gives up Gaia (abdicates, dies,
 * is deposed) the Seed should go with the name, or the next age has a mortal wandering around able
 * to bear Titans. Stamp an item as bound to a role and the engine strips it from anyone the moment
 * they stop holding that role — no per-addon listener required.
 *
 * ```kotlin
 * // In an addon: bind the item to the role. Use context.plugin — the HOST — so the tag is the same
 * // one the engine checks.
 * RoleItems.bind(context.plugin, seed, "gaia")
 * ```
 *
 * **Not everything a role touches should be bound.** The Adamantine Sickle is forged by Gaia and
 * *handed to a child to swing* — it's a world object that passes from hand to hand and can be taken
 * off a corpse. Leave a transferable artifact like that unbound; bind only what makes no sense in
 * anyone else's hands.
 */
object RoleItems {

    /** The tag. Uses the host plugin's namespace, so an addon (via `context.plugin`) and the engine agree. */
    fun key(plugin: Plugin): NamespacedKey = NamespacedKey(plugin, "role_bound")

    /** Stamp an item as belonging to [roleId]. Returns the same stack, for chaining. */
    fun bind(plugin: Plugin, item: ItemStack, roleId: String): ItemStack = item.apply {
        editMeta { it.persistentDataContainer.set(key(plugin), PersistentDataType.STRING, roleId.lowercase()) }
    }

    /** The role an item is bound to, or null if it's free. */
    fun boundRole(plugin: Plugin, item: ItemStack?): String? =
        item?.itemMeta?.persistentDataContainer?.get(key(plugin), PersistentDataType.STRING)

    fun isBound(plugin: Plugin, item: ItemStack?): Boolean = boundRole(plugin, item) != null
}
