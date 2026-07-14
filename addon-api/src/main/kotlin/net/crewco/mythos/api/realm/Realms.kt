package net.crewco.mythos.api.realm

import net.crewco.mythos.api.role.RoleTier
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffectType

/**
 * **A world, and the rules of being in it.** `mythos.realms`
 *
 * The Void is not a region of the overworld with a fancy name — it is a *world*, generated
 * empty, where nothing exists because nothing has been made yet. Gaia is the ground. Tartarus
 * is as far below Hades as the earth is below the sky, and it is a different world because
 * that is what it *is*.
 *
 * A story addon declares its realms; the engine generates them at startup, enforces who may
 * stand in them, and applies whatever it feels like to those who do.
 */
enum class RealmKind {
    /** The server's existing overworld. Gaia. Nobody generates the ground; it's already there. */
    PRIMARY,

    /** Nothing. An empty world with a small platform at spawn, for things that aren't yet. */
    VOID,

    /** A normal generated overworld, of its own. */
    OVERWORLD,

    /** Nether generation — for the places that are hot, or dead, or both. */
    NETHER,

    /** The End's generation. Bleak and floating and wrong. */
    END,

    /** Void, with an island of your chosen stone at [RealmDefinition.platformY]. Olympus. */
    SKY,
}

/** Who may stand here. */
fun interface RealmAccess {
    fun mayEnter(player: Player, ctx: RealmContext): Boolean
}

/** What a rule gets to look at, without reaching into the engine. */
data class RealmContext(
    val roleId: String?,
    val tier: RoleTier?,
    val isSpirit: Boolean,
    val flags: Map<String, Any>,
)

/** The gates you'll actually want. */
object RealmRules {

    val OPEN = RealmAccess { _, _ -> true }

    /** Nobody walks in. You have to be *sent* (`realms.send`). Kronos's stomach. */
    val SENT_ONLY = RealmAccess { _, _ -> false }

    fun roles(vararg roleIds: String) = RealmAccess { _, ctx -> ctx.roleId in roleIds }

    fun tiers(vararg tiers: RoleTier) = RealmAccess { _, ctx -> ctx.tier in tiers }

    /** The gods, and nobody else. This is what makes Olympus mean something. */
    val DIVINE = tiers(RoleTier.PRIMORDIAL, RoleTier.TITAN, RoleTier.OLYMPIAN, RoleTier.CHTHONIC)

    val SPIRITS = RealmAccess { _, ctx -> ctx.isSpirit }

    /** Anyone carrying a flag — "swallowed", "chained", "dead". */
    fun flagged(key: String) = RealmAccess { _, ctx -> ctx.flags.containsKey(key) }

    fun any(vararg rules: RealmAccess) = RealmAccess { player, ctx -> rules.any { it.mayEnter(player, ctx) } }
}

/**
 * A realm. Registered once, by the addon whose story needs it to exist.
 */
data class RealmDefinition(
    /** Lowercase, stable: "void", "gaia", "tartarus", "olympus". */
    val id: String,
    val displayName: String,
    val kind: RealmKind,

    /**
     * The Bukkit world folder. Defaults to `mythos_<id>`. Ignored for [RealmKind.PRIMARY],
     * which uses whatever world the server already has.
     */
    val worldName: String = "mythos_$id",

    /** Who may be here. Anyone else is put back where they came from. */
    val access: RealmAccess = RealmRules.OPEN,

    /** Said to a player as they arrive. MiniMessage. */
    val entryLore: List<String> = emptyList(),
    /** Said to someone the world refuses. */
    val refusal: String = "<gray>You cannot be here. <dark_gray><i>Not yet, and possibly not ever.",

    /** Applied continuously to anyone standing in it. The Void is cold; Tartarus is worse. */
    val ambient: List<PotionEffectType> = emptyList(),

    /** SKY: the height of the island. */
    val platformY: Int = 200,
    /** SKY / VOID: what the platform is made of, and how wide. */
    val platformMaterial: String = "QUARTZ_BLOCK",
    val platformRadius: Int = 24,

    /** Spirits and gods fly here as a matter of course. */
    val flight: Boolean = false,

    /** No mobs, no weather, no time. The Void doesn't have a Tuesday. */
    val still: Boolean = false,
)

/** The engine's map of the cosmos. */
interface RealmService {

    /**
     * Declare a realm. The engine builds it during startup — call this from your `onEnable`
     * and nowhere else, because worlds cannot be created once the server is running.
     */
    fun register(realm: RealmDefinition)

    fun realm(id: String): RealmDefinition?
    fun realms(): List<RealmDefinition>

    /** Null if the world failed to generate — check before you teleport anyone into nothing. */
    fun world(realmId: String): World?

    fun realmOf(world: World): RealmDefinition?

    /** Which realm is this player standing in? */
    fun realmOf(player: Player): RealmDefinition?

    fun spawnOf(realmId: String): Location?

    /** Would the world let them in on their own two feet? */
    fun mayEnter(player: Player, realmId: String): Boolean

    /**
     * Put them there whether it would let them in or not — this is how Uranus buries a Titan
     * and how Kronos swallows a god. Being *sent* somewhere is not the same as being allowed in.
     */
    fun send(player: Player, realmId: String, reason: String = ""): Boolean
}
