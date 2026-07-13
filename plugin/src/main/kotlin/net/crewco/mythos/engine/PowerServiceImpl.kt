package net.crewco.mythos.engine

import net.crewco.mythos.api.event.PowerUseEvent
import net.crewco.mythos.api.power.Power
import net.crewco.mythos.api.power.PowerContext
import net.crewco.mythos.api.power.PowerService
import net.crewco.mythos.command.CommandContext.Companion.mm
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PowerServiceImpl(private val core: MythosEngine) : PowerService {

    private val powers = ConcurrentHashMap<String, Power>()
    private val cooldowns = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>>() // → expiry millis

    override fun register(power: Power) {
        powers[power.id.lowercase()] = power
        core.logger.info("Registered power '${power.id}'")
    }

    override fun power(id: String) = powers[id.lowercase()]

    override fun powersOf(uuid: UUID): List<Power> =
        core.roles.roleOf(uuid)?.powers?.mapNotNull { powers[it.lowercase()] }.orEmpty()

    override fun use(player: Player, powerId: String, args: List<String>): Boolean {
        val id = powerId.lowercase()
        val power = powers[id] ?: run {
            player.sendMessage(mm("<red>No such power."))
            return false
        }
        val role = core.roles.roleOf(player.uniqueId)
        if (role == null || id !in role.powers.map { it.lowercase() }) {
            player.sendMessage(mm("<red>That power is not yours to wield."))
            return false
        }
        val remaining = cooldown(player.uniqueId, id)
        if (remaining > 0) {
            player.sendMessage(mm("<red>${power.displayName} is spent. <gray>(${remaining}s)"))
            return false
        }

        val event = PowerUseEvent(player, id, args)
        Bukkit.getPluginManager().callEvent(event)
        if (event.isCancelled) {
            player.sendMessage(mm("<red>Something smothers your will."))
            return false
        }

        val used = power.use(PowerContext(player, args, PowerContext.Trigger.COMMAND))
        if (used && power.cooldownSeconds > 0) setCooldown(player.uniqueId, id, power.cooldownSeconds.toLong())
        return used
    }

    override fun cooldown(uuid: UUID, powerId: String): Long {
        val until = cooldowns[uuid]?.get(powerId.lowercase()) ?: return 0
        val left = (until - System.currentTimeMillis()) / 1000
        return if (left > 0) left else 0
    }

    override fun setCooldown(uuid: UUID, powerId: String, seconds: Long) {
        cooldowns.getOrPut(uuid) { ConcurrentHashMap() }[powerId.lowercase()] =
            System.currentTimeMillis() + seconds * 1000
    }
}
