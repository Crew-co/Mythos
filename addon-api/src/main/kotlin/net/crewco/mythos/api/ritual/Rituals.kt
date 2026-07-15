package net.crewco.mythos.api.ritual

import net.crewco.mythos.api.trigger.TriggerBinding
import net.crewco.mythos.api.trigger.TriggerService
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player

/**
 * **A thing you do, in steps, with your hands.** `mythos.rituals`
 *
 * [net.crewco.mythos.api.trigger.TriggerService] gives a story single acts. A *ritual* is the
 * sentence those acts spell out: build the Horse and haul it to the gate; bring adamant to the
 * forge and strike it at night; fifty of you stand on the deck at once. The engine wires every
 * step to its trigger, tracks the progress, and fires [Ritual.onComplete] when the last one lands —
 * so a story says *what the rite is* and never touches an event listener.
 *
 * It is also the unit the **Director** watches. A ritual declares [Ritual.minPlayers]; if the house
 * is smaller than that, or the people who could perform it never do, the Director can [RitualHandle.resolve]
 * it a declared way after a grace window, and the story moves on. That is the whole answer to
 * "what happens to the Horse when there are four players and none of them is Trojan".
 */
interface RitualService {

    /** Begin a rite: wire its steps, start tracking. Returns a live handle. */
    fun begin(ritual: Ritual): RitualHandle

    fun active(): List<RitualHandle>
    fun handle(id: String): RitualHandle?

    /** Every ritual an addon began, cancelled when it unloads. **Called by the host.** */
    fun dropFrom(loader: ClassLoader)
}

/** A rite in progress. */
interface RitualHandle {
    val id: String
    val ritual: Ritual

    fun isDone(stepId: String): Boolean
    fun progress(): Int
    fun total(): Int

    /** Tick a step off by hand — for a step a story completes itself, off the world. */
    fun markDone(stepId: String, by: Player? = null)

    /** Finish it now, without the remaining steps — the Director's grace-window escape, or a story that jumps the rite. Fires [Ritual.onResolve] (falling back to onComplete(null)). */
    fun resolve()

    /** Abandon it: unbind every trigger, fire nothing. */
    fun cancel()
}

/**
 * A rite. The [steps] can be struck in any order, or strictly in sequence if [ordered].
 */
data class Ritual(
    val id: String,
    val displayName: String,
    val steps: List<Step>,
    val ordered: Boolean = false,
    val lore: List<String> = emptyList(),

    /**
     * The crowd this rite assumes. The Director treats a house below this as reason to resolve the
     * rite for you rather than let the story hang. Use [net.crewco.mythos.api.dev.DevService.threshold]-style
     * counts here — a rite that needs fifty on a boat should say 50, and dev/small servers cope.
     */
    val minPlayers: Int = 1,

    /** Struck when the last step lands. [by] is whoever landed it (null if the Director resolved it). */
    val onComplete: (by: Player?) -> Unit,

    /** If the Director (or a story) resolves the rite early, this runs instead — narrate it, then do the payload. Defaults to onComplete(null). */
    val onResolve: (() -> Unit)? = null,
)

/**
 * One act in a rite, and how the world satisfies it. Each maps to one or more triggers.
 *
 * `near`, where present, means "within [radius] of here" — a forge, a gate, an altar. Omit it and
 * the act counts anywhere.
 */
sealed interface Step {
    val id: String
    val description: String

    /** Use a marked item. The Seed held up, the bough raised. */
    data class UseItem(override val id: String, override val description: String, val key: NamespacedKey, val near: Location? = null, val radius: Double = 4.0) : Step

    /** Place [count] of a material — building. The timbers of the Horse, the stones of a wall. */
    data class Build(override val id: String, override val description: String, val material: Material, val count: Int, val near: Location? = null, val radius: Double = 10.0) : Step

    /** Right-click a block. An altar-stone, an anvil, a ship's keel. */
    data class Touch(override val id: String, override val description: String, val material: Material, val near: Location? = null, val radius: Double = 4.0) : Step

    /** Lay down or consume a marked item — an offering, a sacrifice, the coin. */
    data class Offer(override val id: String, override val description: String, val key: NamespacedKey, val near: Location? = null, val radius: Double = 4.0) : Step

    /** Step into a place. Onto the ring, into the belly of the Horse. */
    data class Arrive(override val id: String, override val description: String, val at: Location, val radius: Double = 2.0) : Step

    /** A kneel — a sneak-gesture, optionally at a place. Wordless assent. */
    data class Kneel(override val id: String, override val description: String, val near: Location? = null, val radius: Double = 4.0) : Step

    /** [count] players standing within [radius] of [at] at the same time. Fifty on the Argo. */
    data class Gather(override val id: String, override val description: String, val count: Int, val at: Location, val radius: Double = 8.0) : Step

    /** The escape hatch: wire it yourself against the trigger service, call [done] when satisfied. */
    class Custom(override val id: String, override val description: String, val bind: (TriggerService, done: () -> Unit) -> TriggerBinding) : Step
}
