package net.crewco.mythos.api.trigger

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * **The world, as a way to fire a beat.** `mythos.triggers`
 *
 * The engine used to have exactly one way for a player to *do* something in a story: type
 * `/power <id>`. The [net.crewco.mythos.api.power.PowerContext.Trigger] enum has always listed
 * ITEM, PASSIVE and STORY next to COMMAND — but nothing ever produced them, so every myth came
 * out of the chat box. A god swallowed his children by typing their names.
 *
 * This is the missing half. A story binds a beat to an **act in the world** — a strike, a
 * right-click on another player, an item used, a step across a threshold, a block placed, an
 * arrow that lands — and the engine owns one Folia-correct listener that routes the act to
 * whoever registered for it. The command becomes the exception, kept for the few things that
 * genuinely have to be typed (admin, naming an heir), and the myth becomes something you *do*.
 *
 * ```kotlin
 * // EraOfCreation: Uranus no longer types "/power imprison <name>".
 * // He seizes a child with his hands, and the ground takes them.
 * mythos.triggers.onInteractEntity { ctx ->
 *     if (mythos.roles.roleOf(ctx.player.uniqueId)?.id != "uranus") return@onInteractEntity false
 *     val child = ctx.target as? Player ?: return@onInteractEntity false
 *     if (mythos.roles.roleOf(child.uniqueId)?.tier != RoleTier.TITAN) return@onInteractEntity false
 *     mythos.realms.send(child, "tartarus", "The sky closes over you, and keeps closing.")
 *     true   // consumed — the vanilla right-click does nothing
 * }
 * ```
 *
 * **Threading.** A handler runs on the region that owns the *act* — i.e. the acting player's
 * region — so it may touch that player and the block/entity in front of them directly. Reaching
 * any *other* player is illegal from here; schedule onto their region with
 * `context.schedulers.entity(other) { ... }`, exactly as everywhere else in Folia.
 *
 * **Cleanup is automatic.** Every binding remembers the addon that made it and is torn down when
 * that addon unloads — you never have to unregister, and a reload can't leave a dead handler
 * firing from a classloader that no longer exists.
 */
interface TriggerService {

    // ---- the acts a myth is actually made of --------------------------------

    /**
     * Right-click while **holding an item stamped with [key]**. The wand pattern: the Seed of
     * Earth, a coin for the ferryman, the golden bough held up at the cave mouth.
     *
     * Stamp the item with [net.crewco.mythos.api.realm.RealmKeys.mark] (or any PDC byte under
     * [key]); the engine checks the tag, not the material, so the *look* of the object is yours.
     */
    fun onUse(key: NamespacedKey, handler: TriggerHandler): TriggerBinding

    /**
     * Right-click **another entity** — almost always a player. Filter by role inside the handler.
     * Kronos swallowing a god, Uranus seizing a child, Charon taking a coin from the shade in
     * front of him. [TriggerContext.target] is who was clicked.
     */
    fun onInteractEntity(handler: TriggerHandler): TriggerBinding

    /**
     * **Melee-strike an entity while holding an item stamped with [key].** The adamantine sickle:
     * the one object that can unmake the Sky, and it does it by being *swung*, not typed.
     *
     * Runs before the engine's own lethal-damage check, so a handler that means to kill can raise
     * the damage and let core see a fatal blow.
     */
    fun onStrike(key: NamespacedKey, handler: TriggerHandler): TriggerBinding

    /**
     * A projectile **fired from an item stamped with [key]** has landed. Paris' arrow, and the one
     * heel it is allowed to find. The engine copies the tag from the bow onto the arrow at launch,
     * so [TriggerContext.target] is whatever it struck.
     */
    fun onProjectile(key: NamespacedKey, handler: TriggerHandler): TriggerBinding

    /** Right-click a block of [material]. An altar-stone, a ship's timber, the lid of a jar. */
    fun onClickBlock(material: Material, handler: TriggerHandler): TriggerBinding

    /** Place [material]. Building the Horse, raising a wall, sowing the dragon's teeth. */
    fun onPlace(material: Material, handler: TriggerHandler): TriggerBinding

    /** Break [material]. Breaching a seal, tearing down what a god built. */
    fun onBreak(material: Material, handler: TriggerHandler): TriggerBinding

    /** Drop an item stamped with [key]. An offering laid down, a gift refused and thrown back. */
    fun onDrop(key: NamespacedKey, handler: TriggerHandler): TriggerBinding

    /** Eat or drink an item stamped with [key]. Six pomegranate seeds. The cup Circe pours. */
    fun onConsume(key: NamespacedKey, handler: TriggerHandler): TriggerBinding

    /**
     * A **sneak-gesture** — the player pressing shift. A bow of the head, a kneel, a refusal.
     * Cheap, wordless, and exactly the kind of thing that should not need a command. Filter hard
     * inside the handler; everyone sneaks all the time.
     */
    fun onGesture(handler: TriggerHandler): TriggerBinding

    /**
     * **Standing within [radius] of [where].** The callback cousin of a gateway: instead of
     * teleporting the player somewhere, it runs your handler once when they arrive. Stepping onto
     * the birthing-ring, walking into the belly of the Horse, setting foot on the plain of Troy.
     *
     * Polled on each player's own region, like gateways — never by reading a foreign player's
     * position — so it is Folia-legal and does not stutter. The handler fires once per arrival;
     * the player has to leave and come back to fire it again.
     */
    fun onEnter(id: String, where: Location, radius: Double, handler: TriggerHandler): TriggerBinding

    // ---- the general case ---------------------------------------------------

    /**
     * Bind any [WorldAction] with your own filter. The named helpers above are sugar over this;
     * reach for it when you want a combination they don't cover.
     */
    fun bind(action: WorldAction, handler: TriggerHandler): TriggerBinding

    /** Every binding a given addon made, dropped in one go. **Called by the host on unload.** */
    fun dropFrom(loader: ClassLoader)
}

/** The physical acts the engine can route. */
enum class WorldAction {
    USE_ITEM, INTERACT_ENTITY, STRIKE_ENTITY, PROJECTILE_HIT,
    CLICK_BLOCK, PLACE_BLOCK, BREAK_BLOCK, DROP_ITEM, CONSUME_ITEM, GESTURE, ENTER_REGION,
}

/**
 * Everything a handler gets to see, without reaching into the engine. Fields are populated only
 * when they make sense for the [action] — [target] is null for a block act, [block] is null for
 * an entity act, and so on.
 */
data class TriggerContext(
    val action: WorldAction,
    val player: Player,
    val location: Location,
    /** The item in hand (or the marked item that matched), when the act involved one. */
    val item: ItemStack? = null,
    /** The block clicked / placed / broken, when the act involved one. */
    val block: Block? = null,
    /** The entity clicked / struck / hit by a projectile, when the act involved one. */
    val target: Entity? = null,
)

/**
 * What to run when the act happens.
 *
 * Return **true** if you acted on it — the underlying vanilla event is then cancelled (the block
 * isn't really placed, the item isn't really dropped, the right-click does nothing else). Return
 * **false** to wave it through untouched, as if you'd never been asked.
 */
fun interface TriggerHandler {
    fun handle(ctx: TriggerContext): Boolean
}

/** A live binding. Keep it if you want to [remove] it early (a ritual that's been completed). */
interface TriggerBinding {
    val id: String
    val action: WorldAction
    fun remove()
}
