package net.crewco.mythos.api.story

import net.kyori.adventure.audience.Audience
import org.bukkit.entity.Player

/**
 * One line of a scene: a pause, then something happens.
 *
 * The engine plays a list of these on a timer, so a story can *land* instead of
 * arriving as a wall of text. This is the difference between
 *
 *     [Server] Kronos is bound. Era: Olympian Order.
 *
 * and three seconds of silence, a sound like a mountain closing, and one line at a time.
 */
data class Beat(
    /** Ticks to wait BEFORE this beat. 20 = one second. */
    val delayTicks: Long = 40,

    /** A chat line (MiniMessage). Null for a beat that's only a title or a sound. */
    val text: String? = null,

    /** Big centre-screen text (MiniMessage). */
    val title: String? = null,
    val subtitle: String? = null,

    /** e.g. "minecraft:entity.wither.spawn", "minecraft:block.beacon.deactivate". */
    val sound: String? = null,

    /** Fade the screen to black for this beat. Costs nothing; sells everything. */
    val darkness: Boolean = false,
)

/**
 * Stages a scene. `mythos.narrator`.
 *
 * Every story addon should use this rather than hand-rolling broadcasts: the beats are
 * scheduled on the global region, they don't collide with each other, and a player who
 * joins halfway through simply doesn't see the ones that already played.
 */
interface NarratorService {

    /** Play these beats to the whole server, in order. Returns the total length in ticks. */
    fun tell(beats: List<Beat>): Long

    /** Play them to one player. */
    fun tell(player: Player, beats: List<Beat>): Long

    fun tell(audience: Audience, beats: List<Beat>): Long

    /** Is a scene currently playing? (The engine won't stack era transitions.) */
    val isTelling: Boolean
}

/** Sugar for the common case. */
fun beats(build: MutableList<Beat>.() -> Unit): List<Beat> = ArrayList<Beat>().apply(build)

fun MutableList<Beat>.line(text: String, delayTicks: Long = 40) = add(Beat(delayTicks, text = text))

fun MutableList<Beat>.pause(ticks: Long) = add(Beat(ticks))

fun MutableList<Beat>.title(title: String, subtitle: String = "", delayTicks: Long = 40, sound: String? = null) =
    add(Beat(delayTicks, title = title, subtitle = subtitle, sound = sound))
