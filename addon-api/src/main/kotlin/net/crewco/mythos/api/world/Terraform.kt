package net.crewco.mythos.api.world

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block

/**
 * **Undo, for the world.** `mythos.terraform`
 *
 * The flood raises the sea over a whole server. Demeter kills every crop. The Augean Stables bury
 * a field in filth. Every one of those is a *temporary* change to somebody's world, and until now
 * each addon was on its own to put it back — which mostly meant it didn't.
 *
 * A **Scar** is a named change-set. You write blocks through it, it remembers what was there, and
 * later somebody heals it. It survives restarts, because a server that crashes mid-flood should
 * not wake up permanently underwater.
 *
 * ```kotlin
 * val scar = mythos.terraform.scar("flood")
 * scar.set(block, Material.WATER)      // remembers the air that was there
 * ...
 * mythos.terraform.heal("flood")       // the waters go down. Actually down.
 * ```
 *
 * **Permanent damage is a deliberate act.** A thunderbolt crater should still be there next year —
 * so don't open a scar for it, or call [Scar.forget] and let it stand.
 */
interface TerraformService {

    /** Open, or reopen, a named change-set. */
    fun scar(id: String): Scar

    /**
     * Put it all back, oldest change last, on each block's own region thread.
     *
     * Healing a 200,000-block flood is spread across ticks — the server does not freeze while the
     * waters go down, it just takes a moment, the way it should.
     */
    fun heal(id: String)

    fun scars(): List<String>

    /** How many blocks are currently remembered. Useful for `/mythos scars`. */
    fun size(id: String): Int
}

/** A named, revertible change-set. */
interface Scar {

    val id: String

    /**
     * Change a block, remembering what was there first.
     *
     * Folia: must be called on the region that owns the block — i.e. from an entity/region task
     * for a player standing near it. Same rule as touching the block yourself, because that's what
     * this is.
     */
    fun set(block: Block, material: Material)

    /** Convenience for a location. Same threading rule. */
    fun set(location: Location, material: Material)

    fun size(): Int

    /** Let the damage stand. The scar is discarded and the world keeps what you did to it. */
    fun forget()
}
