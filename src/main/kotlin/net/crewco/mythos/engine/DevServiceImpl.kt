package net.crewco.mythos.engine

import net.crewco.mythos.api.dev.DevService
import net.crewco.mythos.command.CommandContext.Companion.mm
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * Solo mode. The reason it exists: you cannot playtest a hundred-player mythology with
 * one player, and pretending otherwise means every number in every story is a guess.
 */
class DevServiceImpl(private val core: MythosEngine) : DevService {

    @Volatile
    private var on: Boolean = false

    override val enabled: Boolean get() = on

    override fun threshold(normal: Int, solo: Int): Int = if (on) solo else normal

    override fun bypasses(player: Player): Boolean = on && player.hasPermission("mythos.admin")

    fun set(value: Boolean, by: String) {
        on = value
        core.saveState()

        // Loud on purpose. A server where one person can be Zeus, Kronos and Gaia in the
        // same afternoon should not be quietly pretending to be anything else.
        Bukkit.getServer().sendMessage(
            if (value) {
                mm("<dark_red>» <red><b>SOLO MODE ON</b> <gray>— gates, costs and cooldowns are off for admins, and every crowd-sized number is now <white>1<gray>. <dark_gray>($by)")
            } else {
                mm("<dark_gray>» <gray>Solo mode off. The world is real again. <dark_gray>($by)")
            },
        )
        core.logger.warning("Dev mode ${if (value) "ENABLED" else "disabled"} by $by")
    }

    internal fun load(value: Boolean) {
        on = value
    }
}
