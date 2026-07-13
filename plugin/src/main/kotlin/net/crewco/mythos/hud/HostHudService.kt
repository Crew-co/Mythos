package net.crewco.mythos.hud

import net.crewco.mythos.MythosPlugin
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Boss bars, sidebar, action bar.
 *
 * Boss bars are Adventure's, and thread-safe. The sidebar is Bukkit's scoreboard,
 * which is not obviously Folia-safe, so every touch of it is scheduled onto the
 * owning player's region and wrapped — a HUD is decoration, and decoration must never
 * be able to take a region thread down with it.
 *
 * The sidebar uses the old team-prefix trick: each line's *entry* is a unique
 * invisible colour code, and the visible text lives in that team's prefix. This is
 * what lets lines update in place without the whole board flickering.
 */
class HostHudService(private val host: MythosPlugin) : HudService, Listener {

    private val bars = ConcurrentHashMap<UUID, MutableMap<String, BossBar>>()
    private val boards = ConcurrentHashMap<UUID, Scoreboard>()

    override fun bossBar(
        player: Player,
        key: String,
        text: Component,
        progress: Float,
        color: BossBar.Color,
        overlay: BossBar.Overlay,
    ) {
        val mine = bars.getOrPut(player.uniqueId) { ConcurrentHashMap() }
        val clamped = progress.coerceIn(0f, 1f)

        val existing = mine[key]
        if (existing != null) {
            // Update in place: no flicker, no re-show.
            existing.name(text)
            existing.progress(clamped)
            existing.color(color)
            return
        }
        val bar = BossBar.bossBar(text, clamped, color, overlay)
        mine[key] = bar
        player.showBossBar(bar)
    }

    override fun removeBossBar(player: Player, key: String) {
        bars[player.uniqueId]?.remove(key)?.let { player.hideBossBar(it) }
    }

    override fun sidebar(player: Player, title: Component, lines: List<Component>) {
        host.schedulers.entity(player) {
            runCatching {
                if (lines.isEmpty()) {
                    clearSidebar(player)
                    return@runCatching
                }
                val board = boards.getOrPut(player.uniqueId) {
                    Bukkit.getScoreboardManager().newScoreboard
                }
                val objective = board.getObjective(OBJECTIVE)
                    ?: board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, title)
                objective.displayName(title)
                objective.displaySlot = DisplaySlot.SIDEBAR

                val shown = lines.take(ENTRIES.size)
                shown.forEachIndexed { index, line ->
                    val entry = ENTRIES[index]
                    val team = board.getTeam("mythos-$index")
                        ?: board.registerNewTeam("mythos-$index").also { it.addEntry(entry) }
                    team.prefix(line)
                    objective.getScore(entry).score = shown.size - index
                }
                // Drop any lines left over from a longer board.
                board.entries.filterNot { it in ENTRIES.take(shown.size) }.forEach { board.resetScores(it) }

                if (player.scoreboard !== board) player.scoreboard = board
            }.onFailure {
                host.logger.fine("Sidebar update skipped for ${player.name}: ${it.message}")
            }
        }
    }

    override fun clearSidebar(player: Player) {
        host.schedulers.entity(player) {
            runCatching {
                boards.remove(player.uniqueId)
                player.scoreboard = Bukkit.getScoreboardManager().mainScoreboard
            }
        }
    }

    override fun actionBar(player: Player, text: Component) {
        player.sendActionBar(text)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        bars.remove(event.player.uniqueId)
        boards.remove(event.player.uniqueId)
    }

    /** Tear down everything one addon put on screen. */
    fun clearAll(uuids: Collection<UUID>, keys: Collection<String>, sidebarOwners: Collection<UUID>) {
        uuids.mapNotNull { Bukkit.getPlayer(it) }.forEach { player ->
            keys.forEach { removeBossBar(player, it) }
        }
        sidebarOwners.mapNotNull { Bukkit.getPlayer(it) }.forEach { clearSidebar(it) }
    }

    private companion object {
        const val OBJECTIVE = "mythos"

        /** Unique, invisible, and stable: legacy colour codes make perfect scoreboard entries. */
        val ENTRIES: List<String> = ('0'..'9').map { "§$it" } + ('a'..'e').map { "§$it" }
    }
}
