package net.crewco.mythos.engine

import net.crewco.mythos.api.ext.ExtensionService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The noticeboard.
 *
 * The only interesting property, and the reason this exists at all: **load order does
 * not matter.** [consume] replays everything already on the board and then keeps
 * listening, so the addon that opens a point and the addon that posts to it can enable
 * in either order — which they will, because the host sorts by `depends:` and an
 * optional extension has no `depends:` to sort by.
 *
 * Without this, "extend another story" means a hard dependency chain, and a hard
 * dependency chain means one missing jar breaks the server.
 */
class ExtensionServiceImpl(private val engine: MythosEngine) : ExtensionService {

    private val board = ConcurrentHashMap<String, CopyOnWriteArrayList<Any>>()
    private val consumers = ConcurrentHashMap<String, CopyOnWriteArrayList<Pair<Class<*>, (Any) -> Unit>>>()

    override fun contribute(point: String, contribution: Any) {
        board.getOrPut(point) { CopyOnWriteArrayList() } += contribution
        engine.logger.info("Extension: ${contribution.javaClass.simpleName} → '$point'")

        consumers[point]?.forEach { (type, consumer) ->
            if (type.isInstance(contribution)) deliver(point, consumer, contribution)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> consume(point: String, type: Class<T>, consumer: (T) -> Unit) {
        val erased = consumer as (Any) -> Unit
        consumers.getOrPut(point) { CopyOnWriteArrayList() } += (type to erased)

        // Replay: everything already posted, delivered now.
        board[point]?.filter { type.isInstance(it) }?.forEach { deliver(point, erased, it) }
    }

    private fun deliver(point: String, consumer: (Any) -> Unit, contribution: Any) {
        runCatching { consumer(contribution) }
            .onFailure { engine.logger.warning("Consumer for '$point' threw on ${contribution.javaClass.name}: ${it.message}") }
    }

    override fun contributions(point: String): List<Any> = board[point].orEmpty().toList()

    override fun points(): List<String> = (board.keys + consumers.keys).sorted()

    /** An addon is unloading: its contributions go with it, or they'd be dead objects. */
    fun dropFrom(loader: ClassLoader) {
        board.values.forEach { list -> list.removeIf { it.javaClass.classLoader === loader } }
        consumers.values.forEach { list -> list.removeIf { it.second.javaClass.classLoader === loader } }
    }
}
