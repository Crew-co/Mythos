package net.crewco.mythos.api.event

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * **The world is being wiped.** Listen to this, or your addon's state survives the reset
 * and haunts the next run.
 *
 * The engine can only reset what the engine owns: eras, roles, spirits, profiles, the
 * chronicle. It has no idea that Titanomachy keeps a kill tally in `war.yml`, or that
 * OlympianOrder remembers where Olympus is. Only your addon knows that — so the engine
 * tells you it happened and gets out of the way.
 *
 * ```kotlin
 * @EventHandler
 * fun onReset(event: MythosResetEvent) {
 *     if (event.scope == MythosResetEvent.Scope.PLAYER) return   // we hold no per-player state
 *     war.clear()
 *     state.clear()
 * }
 * ```
 */
class MythosResetEvent(
    val scope: Scope,
    /** Only set for [Scope.PLAYER]. */
    val uuid: UUID?,
    val by: String,
) : Event() {

    enum class Scope {
        /** Everything. Profiles, essence, history — the world before the world. */
        WORLD,

        /** The story only: eras, objectives, who holds what. Players keep their essence and epithets. */
        STORY,

        /** One player, wiped back to a nameless spirit. */
        PLAYER,
    }

    override fun getHandlers() = HANDLERS

    companion object {
        @JvmStatic val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList() = HANDLERS
    }
}
