package net.crewco.mythos.api.dev

import org.bukkit.entity.Player

/**
 * **Solo mode.** `mythos.dev`
 *
 * This mythology is built for a hundred players, which makes it almost impossible to test
 * with one. The Titanomachy needs 200 cross-faction kills. The lots need three brothers
 * standing in the same room. Twelve thrones need twelve people.
 *
 * So a story addon never hard-codes those numbers — it asks:
 *
 * ```kotlin
 * val killsToEnd = mythos.dev.threshold(config.getInt("war.kills-to-end", 200))  // → 1 in dev
 * if (seated >= mythos.dev.threshold(12)) complete(ERA, "the_twelve", ...)
 * ```
 *
 * Toggle it with `/mythos dev`. It persists across restarts, and the whole server is told
 * when it's on — because a server where one person can be Zeus, Kronos and Gaia in the
 * same afternoon should not be quietly pretending otherwise.
 */
interface DevService {

    val enabled: Boolean

    /**
     * [normal] when the server is real, [solo] when it isn't.
     *
     * Use this for every count that assumes a crowd: kills, thrones, children, prayers.
     */
    fun threshold(normal: Int, solo: Int = 1): Int

    /**
     * Should this player skip the rules entirely — claim gates, essence costs, power
     * cooldowns, claim cooldowns?
     *
     * True only in dev mode, and only for `mythos.admin`. A dev-mode server is still a
     * server; the people who aren't testing it still play by the rules.
     */
    fun bypasses(player: Player): Boolean
}
