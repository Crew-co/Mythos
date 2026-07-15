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

    /**
     * **A cavern.** Solid rock with a hollow band carved out of it, a floor, and no sky. Ever.
     *
     * Use this for Tartarus and the Underworld. It is not a Nether world — it *cannot* be, because
     * worlds are created from `bukkit.yml` (Folia forbids runtime creation) and that cannot set an
     * environment. Which turns out to be a mercy: it is not fire. It is a great grey plain, and it
     * goes on.
     */
    CAVERN,

    /** Nether generation. **Requires runtime world creation — does NOT work on Folia.** Use CAVERN. */
    NETHER,

    /** The End's generation. **Requires runtime world creation — does NOT work on Folia.** */
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

    /** Has a body. The one rule that stops the dead walking home out of the Underworld. */
    val LIVING = RealmAccess { _, ctx -> !ctx.isSpirit }

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

    /**
     * Played, quietly and occasionally, to whoever is standing here. e.g.
     * "minecraft:ambient.cave", "minecraft:entity.ghast.ambient".
     *
     * This does more for the feeling of a place than any amount of chat text. A world that is
     * silent is a world that is a *level*.
     */
    val ambientSound: String? = null,

    /** Drifting, ambient particles. e.g. "SOUL_FIRE_FLAME", "ASH", "END_ROD". */
    val ambientParticle: String? = null,

    /** SKY: the height of the island. CAVERN: the floor you walk on. */
    val platformY: Int = 200,

    /** CAVERN: the roof. Everything above it is solid rock, so there is no sky. */
    val roofY: Int = 120,

    /** CAVERN: what it's made of. */
    val stone: String = "DEEPSLATE",
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

    /**
     * **Open a new way into a realm you didn't write.**
     *
     * The Underworld's access rules belong to whichever addon declared it. This adds an *additional*
     * way in — OR'd with whatever is already there — so a jar written two years later can decide
     * that anyone carrying a golden bough may walk into somebody else's House of the Dead, and that
     * addon needs no change, no knowledge of it, and no version bump.
     *
     * The same mechanism as `roles.extend`, for places instead of people.
     */
    fun grant(realmId: String, access: RealmAccess)

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

    // ---- doors ---------------------------------------------------------------

    /**
     * A mouth in the world that anyone can walk to. The destination realm's access rules still
     * apply — so the entrance to the Underworld can sit in a field in plain sight and still refuse
     * the living, which is the version of that story worth telling.
     */
    fun openGateway(gateway: Gateway)

    fun closeGateway(id: String)

    fun gateways(): List<Gateway>
}
