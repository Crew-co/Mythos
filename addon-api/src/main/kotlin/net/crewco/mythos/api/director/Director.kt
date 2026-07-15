package net.crewco.mythos.api.director

/**
 * **The stage manager — but the call to move on is the admin's, not the clock's.** `mythos.director`
 *
 * This mythology is written for a hundred players and usually run by nine. Two things follow from
 * that, and the Director owns both:
 *
 *  - **Crowd-counts bend to the house.** [scale] takes the number a full server should need and
 *    tapers it toward solo as the house shrinks — so a beat that wants two hundred kills doesn't
 *    strand a nine-player server.
 *
 *  - **A stuck beat can be forced — deliberately.** A story declares, for a required beat, *how* it
 *    would be resolved if it can't happen naturally (nobody claimed the role it needs, the ritual
 *    was never performed). That resolution does **not** fire on a timer. Whoever runs the server
 *    forces it with `/mythos forward` when they judge the story has stalled — which keeps the
 *    turning of an age a decision a person makes, not one the engine makes behind their back.
 *
 * ```kotlin
 * mythos.director.fallback("chaos", "the_unmaking") {
 *     mythos.narrator.tell(beats { line("<dark_red>The Sky was never worn, and it fell of its own weight.") })
 *     mythos.eras.complete("chaos", "the_unmaking", "forwarded: the sky went uncut")
 * }
 * ```
 */
interface DirectorService {

    /**
     * Declare how a required beat of [era] is forced past when it can't happen naturally. The
     * [resolve] block should narrate the shortfall and complete the objective (or
     * [net.crewco.mythos.api.ritual.RitualHandle.resolve] its rite). It runs only when an admin runs
     * `/mythos forward` and this is the next unstruck required beat — never on its own.
     */
    fun fallback(era: String, objective: String, resolve: () -> Unit)

    /**
     * Force the story on by one step: strike the next unstruck **required** beat of the current era —
     * through its declared [fallback] if it has one, otherwise by completing it outright — and, when
     * no required beats remain, turn the age. Returns a short description of what it did, or null if
     * there was nothing to do (no era running). Driven by `/mythos forward`.
     */
    fun forward(reason: String): String?

    /** What the next [forward] would do, described, without doing it. For the admin to look before they leap. */
    fun preview(): String?

    /** How many players are online — the size of the house, audience included. */
    fun houseSize(): Int

    /** Is every one of these roles held by someone right now? */
    fun rolesHeld(vararg roleIds: String): Boolean

    /**
     * A crowd-count, bent to the house: [normal] on a full server, tapering toward [solo] as it
     * empties (and [solo] outright in dev mode). Use it for kills, prayers, thrones — anything that
     * assumes a crowd.
     */
    fun scale(normal: Int, solo: Int = 1): Int

    /** Every fallback an addon declared, dropped when it unloads. **Called by the host.** */
    fun dropFrom(loader: ClassLoader)
}
