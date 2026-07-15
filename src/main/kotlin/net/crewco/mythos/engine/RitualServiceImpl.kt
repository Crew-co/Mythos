package net.crewco.mythos.engine

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.crewco.mythos.api.ritual.Ritual
import net.crewco.mythos.api.ritual.RitualHandle
import net.crewco.mythos.api.ritual.RitualService
import net.crewco.mythos.api.ritual.Step
import net.crewco.mythos.api.trigger.TriggerBinding
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

/**
 * **Rites, tracked.**
 *
 * Composes entirely over [TriggerServiceImpl] — a ritual is a bundle of triggers with a scoreboard.
 * When the last step lands, it fires the payload and unbinds itself; when its addon unloads, every
 * ritual it began is cancelled with it.
 */
class RitualServiceImpl(private val engine: MythosEngine) : RitualService {

    private val active = ConcurrentHashMap<String, Handle>()

    override fun begin(ritual: Ritual): RitualHandle {
        active[ritual.id]?.cancel() // beginning the same rite again replaces the old one
        val handle = Handle(ritual, ritual.onComplete.javaClass.classLoader ?: javaClass.classLoader)
        active[ritual.id] = handle
        handle.wire()
        return handle
    }

    override fun active(): List<RitualHandle> = active.values.toList()
    override fun handle(id: String): RitualHandle? = active[id]

    override fun dropFrom(loader: ClassLoader) {
        active.values.filter { it.owner === loader }.forEach { it.cancel() }
    }

    private fun near(a: Location, b: Location?, radius: Double): Boolean {
        if (b == null) return true
        if (a.world != b.world) return false
        return a.distanceSquared(b) <= radius * radius
    }

    // -------------------------------------------------------------------------

    private inner class Handle(override val ritual: Ritual, val owner: ClassLoader) : RitualHandle {
        override val id get() = ritual.id

        private val done = ConcurrentHashMap.newKeySet<String>()
        private val counts = ConcurrentHashMap<String, Int>()      // Build progress
        private val bindings = mutableListOf<TriggerBinding>()
        private val polls = mutableListOf<ScheduledTask>()
        @Volatile private var finished = false

        override fun isDone(stepId: String) = stepId in done
        override fun progress() = done.size
        override fun total() = ritual.steps.size

        /** Ordered rites only accept the next undone step; unordered accept any undone step. */
        private fun available(stepId: String): Boolean {
            if (stepId in done) return false
            if (!ritual.ordered) return true
            return ritual.steps.firstOrNull { it.id !in done }?.id == stepId
        }

        fun wire() {
            val t = engine.triggers
            ritual.steps.forEach { step ->
                when (step) {
                    is Step.UseItem -> bindings += t.onUse(step.key) { ctx ->
                        if (available(step.id) && near(ctx.location, step.near, step.radius)) { satisfy(step.id, ctx.player); true } else false
                    }
                    is Step.Touch -> bindings += t.onClickBlock(step.material) { ctx ->
                        if (available(step.id) && near(ctx.location, step.near, step.radius)) { satisfy(step.id, ctx.player); true } else false
                    }
                    is Step.Offer -> {
                        bindings += t.onDrop(step.key) { ctx -> if (available(step.id) && near(ctx.location, step.near, step.radius)) { satisfy(step.id, ctx.player); true } else false }
                        bindings += t.onConsume(step.key) { ctx -> if (available(step.id) && near(ctx.location, step.near, step.radius)) { satisfy(step.id, ctx.player); true } else false }
                    }
                    is Step.Arrive -> bindings += t.onEnter("${ritual.id}-${step.id}", step.at, step.radius) { ctx ->
                        if (available(step.id)) satisfy(step.id, ctx.player); false
                    }
                    is Step.Kneel -> bindings += t.onGesture { ctx ->
                        if (available(step.id) && near(ctx.location, step.near, step.radius)) { satisfy(step.id, ctx.player); true } else false
                    }
                    is Step.Build -> bindings += t.onPlace(step.material) { ctx ->
                        if (!available(step.id) || !near(ctx.location, step.near, step.radius)) return@onPlace false
                        val n = counts.merge(step.id, 1, Int::plus)!!
                        if (n >= step.count) satisfy(step.id, ctx.player)
                        false // let the block actually be placed — they're building something real
                    }
                    is Step.Gather -> polls += engine.schedulers.regionRepeating(step.at, 20, 20) { task ->
                        if (finished) { task.cancel(); return@regionRepeating }
                        if (!available(step.id)) return@regionRepeating
                        val here = step.at.getNearbyPlayers(step.radius).size
                        if (here >= step.count) satisfy(step.id, step.at.getNearbyPlayers(step.radius).firstOrNull())
                    }
                    is Step.Custom -> bindings += step.bind(t) { if (available(step.id)) satisfy(step.id, null) }
                }
            }
        }

        private fun satisfy(stepId: String, by: Player?) {
            if (finished || stepId in done) return
            done += stepId
            if (done.size >= ritual.steps.size) complete(by)
        }

        override fun markDone(stepId: String, by: Player?) = engine.schedulers.global { satisfy(stepId, by) }.let {}

        private fun complete(by: Player?) {
            if (finished) return
            finished = true
            teardown()
            active.remove(ritual.id)
            runCatching { ritual.onComplete(by) }
                .onFailure { engine.logger.warning("Ritual '${ritual.id}' onComplete threw: ${it.message}") }
        }

        override fun resolve() {
            if (finished) return
            finished = true
            teardown()
            active.remove(ritual.id)
            val payload = ritual.onResolve ?: { ritual.onComplete(null) }
            runCatching { payload() }
                .onFailure { engine.logger.warning("Ritual '${ritual.id}' onResolve threw: ${it.message}") }
        }

        override fun cancel() {
            if (finished) return
            finished = true
            teardown()
            active.remove(ritual.id)
        }

        private fun teardown() {
            bindings.forEach { runCatching { it.remove() } }
            polls.forEach { runCatching { it.cancel() } }
            bindings.clear(); polls.clear()
        }
    }
}
