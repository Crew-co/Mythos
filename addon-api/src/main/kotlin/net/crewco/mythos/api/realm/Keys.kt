package net.crewco.mythos.api.realm

import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/**
 * **The thing in your pocket is the reason you're allowed to be here.**
 *
 * Not every mythology has a friendly god on hand to unlock the underworld for you, and Greek myth
 * mostly didn't either — it had **objects**. A golden bough. A cap that makes you nobody. A coin
 * under your tongue. The mortal doesn't get let in; the mortal *brings something*.
 *
 * So an item can be a key:
 *
 * ```kotlin
 * val BOUGH = RealmKeys.key(context.plugin, "golden-bough")
 *
 * // Persephone makes one:
 * RealmKeys.mark(ItemStack(Material.GOLDEN_CARROT), BOUGH)
 *
 * // And the House of Hades quietly starts admitting whoever is carrying it:
 * mythos.realms.grant("underworld", RealmKeys.bearing(BOUGH))
 * ```
 *
 * The engine has no opinion about what the item *is*. It checks a tag. Everything else — what it
 * looks like, who can make one, whether it burns up on use, whether it can be stolen — is the
 * story's business, which is exactly where it belongs.
 */
object RealmKeys {

    fun key(plugin: Plugin, id: String): NamespacedKey = NamespacedKey(plugin, "realmkey_$id")

    /** Stamp an item. Returns the same stack, for chaining. */
    fun mark(item: ItemStack, key: NamespacedKey): ItemStack = item.apply {
        editMeta { meta -> meta.persistentDataContainer.set(key, PersistentDataType.BYTE, 1) }
    }

    fun isKey(item: ItemStack?, key: NamespacedKey): Boolean =
        item?.itemMeta?.persistentDataContainer?.has(key, PersistentDataType.BYTE) == true

    /**
     * Admits anyone with the key **anywhere on them**. Good for a thing you carry, like a bough.
     *
     * Note the consequence, and it is a feature: **keys can be stolen, dropped, and taken off your
     * corpse.** A mortal who can walk into the House of Hades can be robbed of the ability on the
     * way there, by another player, who then can.
     */
    fun bearing(key: NamespacedKey) = RealmAccess { player, _ ->
        player.inventory.contents.any { isKey(it, key) }
    }

    /**
     * Admits only someone **holding it in their hand**, which is a great deal more dramatic and a
     * great deal easier to lose at the worst possible moment.
     */
    fun holding(key: NamespacedKey) = RealmAccess { player, _ ->
        isKey(player.inventory.itemInMainHand, key) || isKey(player.inventory.itemInOffHand, key)
    }

    /** Wearing it. For a cap that makes you nobody. */
    fun wearing(key: NamespacedKey) = RealmAccess { player, _ ->
        player.inventory.armorContents.any { isKey(it, key) }
    }
}
