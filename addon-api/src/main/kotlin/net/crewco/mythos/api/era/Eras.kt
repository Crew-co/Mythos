package net.crewco.mythos.api.era

import net.crewco.mythos.api.story.Beat

/**
 * A beat of the story that can be ticked off: "Uranus imprisoned his children",
 * "the sickle was forged". An era ends when every non-optional objective is done,
 * and then the world moves on to [EraDefinition.next] — which is usually a role
 * a *different addon* registered. That's the whole spine of this project.
 */
data class Objective(
    val id: String,
    val description: String,
    /** Hidden objectives don't show in `/era` until completed. Keeps twists twisty. */
    val hidden: Boolean = false,
    /** Optional objectives don't block the era from ending. Side quests, essentially. */
    val optional: Boolean = false,
)

/**
 * One chapter. Registered by exactly one addon, which is also responsible for the
 * mechanics that complete its objectives.
 */
data class EraDefinition(
    /** Lowercase, unique, stable. e.g. "chaos", "titanomachy", "trojan-war". */
    val id: String,
    val displayName: String,
    /** Chronological position — 0 is the void before anything. */
    val order: Int,
    /**
     * The era that follows when this one completes. Null = the story ends here (for now).
     *
     * This is a *default*, not a law: another addon can splice a chapter in between with
     * [EraService.insertAfter], and the chain re-links itself. You never edit a neighbour.
     */
    val next: String?,
    val objectives: List<Objective>,
    val lore: List<String> = emptyList(),
    /** Shown in the boss bar while this era runs. */
    val subtitle: String = "",

    /**
     * Played, beat by beat, as this era BEGINS. The curtain going up.
     * (See `net.crewco.mythos.api.story.beats { }`.)
     */
    val prologue: List<Beat> = emptyList(),

    /**
     * Played as this era ENDS, before the next one's prologue. The curtain coming down.
     *
     * The engine holds the world still while these play — nothing can be claimed
     * mid-transition — so a story gets to *finish* instead of being trampled by the
     * next one starting.
     */
    val epilogue: List<Beat> = emptyList(),
)

/** The world's clock. One era is current at a time, server-wide. */
interface EraService {

    fun register(era: EraDefinition)

    // ---- splicing: how a later addon edits a story it didn't write ----------

    /**
     * Put a new chapter in immediately after an existing one. The chain re-links:
     * `after.next` becomes your era, and your era's `next` becomes whatever `after`
     * used to point at.
     *
     * This is the worldbuilding primitive. Someone writes "The Rape of Persephone" three
     * years from now and drops it between the Olympian Order and the Heroic Age —
     * without touching either jar, and without either jar knowing.
     */
    fun insertAfter(afterEraId: String, era: EraDefinition)

    /** The same, the other way round. */
    fun insertBefore(beforeEraId: String, era: EraDefinition)

    /** Whatever currently follows this era — the override if one was spliced in, else its declared `next`. */
    fun nextOf(eraId: String): String?

    /** The story as it now stands: walked from the first era through the (possibly rewired) chain. */
    fun chain(): List<EraDefinition>

    /**
     * Add a beat to somebody else's chapter. An optional objective is a side-story; a
     * required one genuinely holds the world back until it's done, so be sure.
     */
    fun addObjective(eraId: String, objective: Objective)

    /**
     * True while an era is ending and the next is beginning — the epilogue is playing,
     * the world is between ages. Claiming is refused; let the scene land.
     */
    val isTransitioning: Boolean

    fun current(): EraDefinition?
    fun currentId(): String
    fun era(id: String): EraDefinition?
    fun eras(): List<EraDefinition>

    /** Has this era already been and gone? Use it to gate "the age has passed" content. */
    fun isPast(eraId: String): Boolean

    fun objectives(eraId: String): List<Objective>
    fun isComplete(eraId: String, objectiveId: String): Boolean

    /**
     * Tick off a beat. If that was the last required one, core fires
     * EraAdvancedEvent and moves the world to [EraDefinition.next] on its own.
     */
    fun complete(eraId: String, objectiveId: String, reason: String = "")

    /** Force the world forward (admin, or a story that jumps the rails). */
    fun advance(toEraId: String, reason: String): Boolean
}
