package net.crewco.mythos.hud

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

/**
 * `context.hud` — boss bars, a sidebar, and the action bar, without every addon
 * hand-rolling scoreboard plumbing and then fighting each other over it.
 *
 * Boss bars are **keyed**, so two addons can each own one and neither can stomp the
 * other's. Everything an addon shows is torn down when that addon unloads.
 *
 * Every call is safe from any thread — the host hops onto the player's region for you.
 */
interface HudService {

    /** Show or update a keyed boss bar. Same key = update in place, no flicker. */
    fun bossBar(
        player: Player,
        key: String,
        text: Component,
        progress: Float = 1f,
        color: BossBar.Color = BossBar.Color.WHITE,
        overlay: BossBar.Overlay = BossBar.Overlay.PROGRESS,
    )

    fun removeBossBar(player: Player, key: String)

    /**
     * The sidebar. Pass at most 15 lines; pass an empty list to clear it.
     *
     * One sidebar per server, not per addon — if two addons both want it, the last
     * one to call wins, which is exactly how vanilla scoreboards behave anyway.
     */
    fun sidebar(player: Player, title: Component, lines: List<Component>)

    fun clearSidebar(player: Player)

    fun actionBar(player: Player, text: Component)
}
