package net.crewco.mythos.engine

import net.crewco.mythos.api.trigger.TriggerBinding
import net.crewco.mythos.api.trigger.TriggerContext
import net.crewco.mythos.api.trigger.TriggerHandler
import net.crewco.mythos.api.trigger.TriggerService
import net.crewco.mythos.api.trigger.WorldAction
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * **The world → beat router.**
 *
 * One registry, one listener ([TriggerListener]), one poll loop. A story registers a binding and
 * forgets about it; the engine remembers which addon's classloader made it and drops it on unload,
 * the same way [ExtensionServiceImpl.dropFrom] drops contributions — so a reload can never leave a
 * handler firing out of a dead jar.
 *
 * Dispatch always runs on the region that owns the act (the acting player's region), because it is
 * driven from Bukkit events, which Folia already fires there. That is the whole reason a handler is
 * allowed to touch the player and the thing in front of them without scheduling.
 */
class TriggerServiceImpl(private val engine: MythosEngine) : TriggerService {

    /** One binding. [key]/[material] are the built-in pre-filters; the handler does the rest. */
    private inner class Binding(
        override val id: String,
        override val action: WorldAction,
        val key: NamespacedKey?,
        val material: Material?,
        val handler: TriggerHandler,
        val owner: ClassLoader,
        // onEnter only:
        val where: Location? = null,
        val radius: Double = 0.0,
    ) : TriggerBinding {
        /** For onEnter: who is currently inside, so the handler fires once per arrival. */
        val inside: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        override fun remove() { bindings[action]?.remove(this); recomputeProjectileKeys() }
    }

    private val bindings = ConcurrentHashMap<WorldAction, CopyOnWriteArrayList<Binding>>()
    private val counter = AtomicLong()

    /** The set of keys any onProjectile binding cares about — cached so launch-tagging is cheap. */
    @Volatile private var projectileKeyCache: Set<NamespacedKey> = emptySet()

    private fun recomputeProjectileKeys() {
        projectileKeyCache = bindings[WorldAction.PROJECTILE_HIT].orEmpty().mapNotNull { it.key }.toSet()
    }

    private fun add(binding: Binding): TriggerBinding {
        bindings.getOrPut(binding.action) { CopyOnWriteArrayList() } += binding
        if (binding.action == WorldAction.PROJECTILE_HIT) recomputeProjectileKeys()
        return binding
    }

    private fun loaderOf(handler: TriggerHandler): ClassLoader =
        handler.javaClass.classLoader ?: javaClass.classLoader

    private fun nextId(action: WorldAction) = "${action.name.lowercase()}-${counter.incrementAndGet()}"

    // ---- registration -------------------------------------------------------

    override fun onUse(key: NamespacedKey, handler: TriggerHandler) =
        add(Binding(nextId(WorldAction.USE_ITEM), WorldAction.USE_ITEM, key, null, handler, loaderOf(handler)))

    override fun onInteractEntity(handler: TriggerHandler) =
        add(Binding(nextId(WorldAction.INTERACT_ENTITY), WorldAction.INTERACT_ENTITY, null, null, handler, loaderOf(handler)))

    override fun onStrike(key: NamespacedKey, handler: TriggerHandler) =
        add(Binding(nextId(WorldAction.STRIKE_ENTITY), WorldAction.STRIKE_ENTITY, key, null, handler, loaderOf(handler)))

    override fun onProjectile(key: NamespacedKey, handler: TriggerHandler) =
        add(Binding(nextId(WorldAction.PROJECTILE_HIT), WorldAction.PROJECTILE_HIT, key, null, handler, loaderOf(handler)))

    override fun onClickBlock(material: Material, handler: TriggerHandler) =
        add(Binding(nextId(WorldAction.CLICK_BLOCK), WorldAction.CLICK_BLOCK, null, material, handler, loaderOf(handler)))

    override fun onPlace(material: Material, handler: TriggerHandler) =
        add(Binding(nextId(WorldAction.PLACE_BLOCK), WorldAction.PLACE_BLOCK, null, material, handler, loaderOf(handler)))

    override fun onBreak(material: Material, handler: TriggerHandler) =
        add(Binding(nextId(WorldAction.BREAK_BLOCK), WorldAction.BREAK_BLOCK, null, material, handler, loaderOf(handler)))

    override fun onDrop(key: NamespacedKey, handler: TriggerHandler) =
        add(Binding(nextId(WorldAction.DROP_ITEM), WorldAction.DROP_ITEM, key, null, handler, loaderOf(handler)))

    override fun onConsume(key: NamespacedKey, handler: TriggerHandler) =
        add(Binding(nextId(WorldAction.CONSUME_ITEM), WorldAction.CONSUME_ITEM, key, null, handler, loaderOf(handler)))

    override fun onGesture(handler: TriggerHandler) =
        add(Binding(nextId(WorldAction.GESTURE), WorldAction.GESTURE, null, null, handler, loaderOf(handler)))

    override fun onEnter(id: String, where: Location, radius: Double, handler: TriggerHandler) =
        add(Binding("enter-$id", WorldAction.ENTER_REGION, null, null, handler, loaderOf(handler), where, radius))

    override fun bind(action: WorldAction, handler: TriggerHandler) =
        add(Binding(nextId(action), action, null, null, handler, loaderOf(handler)))

    override fun dropFrom(loader: ClassLoader) {
        var removed = 0
        bindings.values.forEach { list -> removed += list.count { it.owner === loader }; list.removeIf { it.owner === loader } }
        recomputeProjectileKeys()
        if (removed > 0) engine.logger.info("Dropped $removed trigger(s) from an unloading addon.")
    }

    // ---- dispatch (called by the listener, already on the right region) -----

    fun hasKey(item: ItemStack?, key: NamespacedKey): Boolean =
        item?.itemMeta?.persistentDataContainer?.has(key, PersistentDataType.BYTE) == true

    /** True if any handler consumed the act (the caller should cancel the vanilla event). */
    fun dispatch(action: WorldAction, ctx: TriggerContext, keyed: (NamespacedKey) -> Boolean = { true }, mat: Material? = null): Boolean {
        val list = bindings[action] ?: return false
        var consumed = false
        for (b in list) {
            if (b.key != null && !keyed(b.key)) continue
            if (b.material != null && b.material != mat) continue
            val handled = runCatching { b.handler.handle(ctx) }
                .onFailure { engine.logger.warning("Trigger '${b.id}' threw: ${it.message}") }
                .getOrDefault(false)
            if (handled) consumed = true
        }
        return consumed
    }

    /** The keys that must be copied onto a projectile at launch (any of them the shooter holds). */
    fun projectileKeysHeldBy(shooter: Player): List<NamespacedKey> =
        projectileKeyCache.filter { hasKey(shooter.inventory.itemInMainHand, it) || hasKey(shooter.inventory.itemInOffHand, it) }

    fun projectileMatches(projectilePdc: org.bukkit.persistence.PersistentDataContainer, key: NamespacedKey): Boolean =
        projectilePdc.has(key, PersistentDataType.BYTE)

    // ---- enter-region poll --------------------------------------------------
    //
    // The same shape as Gateways: never read a foreign player's location. A global tick fans out to
    // each player's own region, which reads that player's position and tests the enter-bindings.

    fun start() {
        engine.schedulers.globalRepeating(20, 10) {
            val enters = bindings[WorldAction.ENTER_REGION] ?: return@globalRepeating
            if (enters.isEmpty()) return@globalRepeating
            org.bukkit.Bukkit.getOnlinePlayers().forEach { player ->
                engine.schedulers.entity(player) {
                    val loc = player.location
                    for (b in enters) {
                        val where = b.where ?: continue
                        if (where.world != loc.world) { b.inside.remove(player.uniqueId); continue }
                        val near = where.distanceSquared(loc) <= b.radius * b.radius
                        val wasInside = player.uniqueId in b.inside
                        if (near && !wasInside) {
                            b.inside += player.uniqueId
                            val ctx = TriggerContext(WorldAction.ENTER_REGION, player, loc)
                            runCatching { b.handler.handle(ctx) }
                                .onFailure { engine.logger.warning("Enter-trigger '${b.id}' threw: ${it.message}") }
                        } else if (!near && wasInside) {
                            b.inside -= player.uniqueId
                        }
                    }
                }
            }
        }
    }
}
