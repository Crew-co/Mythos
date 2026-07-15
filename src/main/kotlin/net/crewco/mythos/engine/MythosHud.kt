package net.crewco.mythos.engine

import net.crewco.mythos.api.event.EraAdvancedEvent
import net.crewco.mythos.api.event.ObjectiveCompletedEvent
import net.crewco.mythos.api.event.RoleClaimedEvent
import net.crewco.mythos.api.event.RoleReleasedEvent
import net.crewco.mythos.command.CommandContext.Companion.mm
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/**
 * The age, on screen, always.
 *
 * A boss bar with the current era and how much of it is done, and a sidebar with who
 * you are, what you're worth, and where you stand in the queue. All of it on the
 * host's HUD service, so this file is about mythology rather than scoreboard teams.
 *
 * It's the thing that makes the server feel like a *story in progress* rather than a
 * survival world with roles bolted on: you can see the age advancing.
 */
class MythosHud(private val core: MythosEngine) : Listener {

    fun start() {
        if (!core.config.hudEnabled) return
        // Cheap: a boss bar update is a packet, and the sidebar is scheduled onto each
        // player's own region. Every 3s is plenty for essence and queue positions.
        core.schedulers.globalRepeating(40, 60) {
            Bukkit.getOnlinePlayers().forEach { update(it) }
        }
    }

    fun update(player: Player) {
        if (!core.config.hudEnabled) return
        val era = core.eras.current()
        val uuid = player.uniqueId
        val role = core.roles.roleOf(uuid)
        val spirit = core.spirits.isSpirit(uuid)

        if (era != null) {
            // From the service, not the definition: another addon may have bolted an
            // objective onto this chapter.
            val required = core.eras.objectives(era.id).filterNot { it.optional }
            val done = required.count { core.eras.isComplete(era.id, it.id) }
            val progress = if (required.isEmpty()) 1f else done.toFloat() / required.size

            core.hud.bossBar(
                player = player,
                key = "mythos:era",
                text = mm("<gold>${era.displayName} <dark_gray>· <gray>$done<dark_gray>/<gray>${required.size}"),
                progress = progress,
                color = BossBar.Color.PURPLE,
            )
        }

        val lines = buildList {
            if (era != null) {
                add(mm("<gray>The age of <white>${era.displayName}"))
                // The next thing that has to happen — the whole server's to-do list.
                val next = core.eras.objectives(era.id).firstOrNull {
                    !it.optional && !it.hidden && !core.eras.isComplete(era.id, it.id)
                }
                if (next != null) add(mm("<dark_gray>» <white>${next.description}"))
                add(mm(" "))
            }
            if (role != null) {
                add(mm("<gray>You are ${role.color}${role.displayName}"))
                add(mm("<dark_gray>${role.tier.displayName}"))
            } else {
                add(mm("<dark_gray>You are nobody. <gray>Yet."))
                if (spirit) {
                    val position = core.spirits.queuePosition(uuid)
                    add(mm("<gray>In line: <white>${if (position < 0) "not queued" else "#${position + 1}"}"))
                }
            }
            add(mm("<gray>Essence: <white>${core.spirits.essence(uuid)}"))
        }

        core.hud.sidebar(player, mm("<gold><b>MYTHOS"), lines)
    }

    private fun updateEveryone() = Bukkit.getOnlinePlayers().forEach { update(it) }

    @EventHandler fun onJoin(event: PlayerJoinEvent) =
        core.schedulers.entityDelayed(event.player, 20, retired = null) { update(event.player) }.let { }

    @EventHandler fun onEra(event: EraAdvancedEvent) = updateEveryone()
    @EventHandler fun onObjective(event: ObjectiveCompletedEvent) = updateEveryone()
    @EventHandler fun onClaimed(event: RoleClaimedEvent) = update(event.player)
    @EventHandler fun onReleased(event: RoleReleasedEvent) = event.player?.let { update(it) } ?: Unit
}
