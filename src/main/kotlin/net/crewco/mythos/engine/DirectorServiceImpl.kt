package net.crewco.mythos.engine

import net.crewco.mythos.api.director.DirectorService
import org.bukkit.Bukkit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * **The stage manager, on the admin's word.**
 *
 * No watch loop, no timer: the Director never turns an age on its own. It keeps the map of "how to
 * force this beat" that stories declare, scales crowd-counts to the house, and — only when
 * `/mythos forward` is run — strikes the next stuck beat or turns the age.
 */
class DirectorServiceImpl(private val engine: MythosEngine) : DirectorService {

    private val referenceCrowd = 40

    private class Fallback(val resolve: () -> Unit, val owner: ClassLoader)

    private val fallbacks = ConcurrentHashMap<String, Fallback>()
    private fun key(era: String, objective: String) = "$era\u0000$objective"

    override fun fallback(era: String, objective: String, resolve: () -> Unit) {
        fallbacks[key(era, objective)] = Fallback(resolve, resolve.javaClass.classLoader ?: javaClass.classLoader)
    }

    override fun forward(reason: String): String? {
        if (engine.eras.isTransitioning) return "The age is turning — let it land, then forward again."
        val era = engine.eras.current() ?: return null
        val next = engine.eras.objectives(era.id).firstOrNull { !it.optional && !engine.eras.isComplete(era.id, it.id) }

        if (next == null) {
            val to = engine.eras.nextOf(era.id)
                ?: return "'${era.displayName}' is finished, and no further chapter is installed."
            engine.eras.advance(to, reason)
            return "Every beat of '${era.displayName}' is struck — the age turns toward '$to'."
        }

        val fb = fallbacks[key(era.id, next.id)]
        return if (fb != null) {
            runCatching { fb.resolve() }
                .onFailure { engine.logger.warning("Director fallback for '${era.id}/${next.id}' threw: ${it.message}") }
            "Forwarded '${next.id}' — ${next.description} (via its declared fallback)."
        } else {
            engine.eras.complete(era.id, next.id, reason)
            "Struck '${next.id}' — ${next.description}."
        }
    }

    override fun preview(): String? {
        if (engine.eras.isTransitioning) return "The age is turning."
        val era = engine.eras.current() ?: return null
        val next = engine.eras.objectives(era.id).firstOrNull { !it.optional && !engine.eras.isComplete(era.id, it.id) }
            ?: return "'${era.displayName}' is finished; forwarding would turn the age."
        val via = if (fallbacks.containsKey(key(era.id, next.id))) "its declared fallback" else "a plain strike"
        return "Next: '${next.id}' — ${next.description} (via $via)."
    }

    override fun houseSize(): Int = Bukkit.getOnlinePlayers().size

    override fun rolesHeld(vararg roleIds: String): Boolean =
        roleIds.all { engine.roles.holders(it).isNotEmpty() }

    override fun scale(normal: Int, solo: Int): Int {
        if (engine.dev.enabled) return solo
        val house = houseSize()
        if (house <= 0) return solo
        val scaled = ceil(normal.toDouble() * min(1.0, house.toDouble() / referenceCrowd)).toInt()
        return max(solo, scaled)
    }

    override fun dropFrom(loader: ClassLoader) {
        fallbacks.values.removeIf { it.owner === loader }
    }
}
