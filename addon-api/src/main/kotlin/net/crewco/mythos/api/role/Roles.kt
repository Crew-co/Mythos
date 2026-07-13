package net.crewco.mythos.api.role

import org.bukkit.Statistic
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Where a role sits in the cosmic pecking order. Tiers drive default stats,
 * who can permanently kill whom, and how a role is inherited when it falls vacant.
 */
enum class RoleTier(val displayName: String, val heartsBonus: Int) {
    PRIMORDIAL("Primordial", 40),   // Chaos, Gaia, Uranus, Nyx...
    TITAN("Titan", 24),             // Kronos, Rhea, Oceanus...
    OLYMPIAN("Olympian", 30),       // Zeus, Hera, Poseidon...
    CHTHONIC("Chthonic", 20),       // Hades, Persephone, the Erinyes
    MONSTER("Monster", 16),         // Typhon, the Hydra, Medusa
    DEMIGOD("Demigod", 10),         // Heracles, Perseus, Achilles
    HERO("Hero", 6),                // Odysseus, Theseus, Atalanta
    MORTAL("Mortal", 0),            // the crowd of Argos
    ;

    /** Gods need gods. A mortal cannot put down a Titan, whatever the sword. */
    fun killableBy(other: RoleTier): Boolean = when (this) {
        PRIMORDIAL -> other == PRIMORDIAL || other == TITAN
        TITAN, OLYMPIAN, CHTHONIC -> other.ordinal <= MONSTER.ordinal
        else -> true
    }
}

/**
 * Does this role survive the turning of an age?
 *
 * When one story ends and the next begins, the cast of the old story should not still
 * be wandering around wearing its names. The soldiers of a finished war go home; the
 * Earth does not.
 */
enum class Endurance {
    /** Untouched by the turning of an age. Gaia, Zeus, the Cyclopes. The default. */
    ETERNAL,

    /**
     * The cast of one story. When its era ends, the holder is returned to the spirit
     * world (with essence, and an epithet: "Once Nyx") and the role is sealed until an
     * addon deliberately reopens it.
     *
     * A downstream addon can keep a specific character on stage by cancelling
     * `RoleRetiringEvent` — that's how the Odyssey holds on to Odysseus after the Iliad.
     */
    ERA,
}

/** What happens to a role when its holder dies, quits for good, or is deposed. */
enum class Succession {
    /** Offered to the front of the spirit queue. The default. */
    QUEUE,

    /** Passed to a named heir, if the holder named one (`/role heir <player>`). */
    HEIR,

    /** Sealed until an addon reopens it — Uranus, after the sickle. */
    CLOSED,
}

/** The verdict on a claim attempt. */
sealed interface ClaimResult {
    data object Allow : ClaimResult
    data class Deny(val reason: String) : ClaimResult
}

/** Everything a rule needs to judge a claim, without touching core internals. */
data class ClaimContext(
    val roleId: String,
    val currentEra: String,
    /** Every era the world has already been through. Lets a rule say "this era, or any time after". */
    val pastEras: Set<String>,
    val holders: Set<UUID>,
    val essence: Int,
    /** Position in the spirit queue; -1 if they aren't queued at all. */
    val queuePosition: Int,
)

/** A gate on claiming a role. Compose as many as you like — ALL must allow. */
fun interface ClaimRule {
    fun evaluate(player: Player, ctx: ClaimContext): ClaimResult
}

/** The gates you'll actually reach for. */
object ClaimRules {

    /** Permission node — this is the "only certain players" lever. */
    fun permission(node: String) = ClaimRule { player, _ ->
        if (player.hasPermission(node)) ClaimResult.Allow
        else ClaimResult.Deny("The Fates have not written your name for this.")
    }

    /** A hand-picked cast for a story arc. */
    fun whitelist(allowed: Set<UUID>) = ClaimRule { player, _ ->
        if (player.uniqueId in allowed) ClaimResult.Allow
        else ClaimResult.Deny("This mantle was promised to another.")
    }

    /** Hours logged before you may touch a god slot. */
    fun minPlaytimeHours(hours: Int) = ClaimRule { player, _ ->
        val played = player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 3600
        if (played >= hours) ClaimResult.Allow
        else ClaimResult.Deny("You are too newly-born. ($played/$hours hours)")
    }

    /** Paid in essence, which spirits earn by haunting the living world. */
    fun essenceCost(amount: Int) = ClaimRule { _, ctx ->
        if (ctx.essence >= amount) ClaimResult.Allow
        else ClaimResult.Deny("You need $amount essence; you have ${ctx.essence}.")
    }

    /**
     * ONLY during these eras. No new Titans after the Titanomachy.
     *
     * Careful: this makes the role permanently unclaimable once the age turns. That's
     * right for a Titan and wrong for Gaia — the Earth doesn't stop existing because
     * we've moved on to Troy. For a role that should outlive its own era, use [sinceEra].
     */
    fun duringEra(vararg eraIds: String) = ClaimRule { _, ctx ->
        if (ctx.currentEra in eraIds) ClaimResult.Allow
        else ClaimResult.Deny("That age has passed.")
    }

    /** From this era onwards, forever. The right default for gods who persist. */
    fun sinceEra(eraId: String) = ClaimRule { _, ctx ->
        if (ctx.currentEra == eraId || eraId in ctx.pastEras) ClaimResult.Allow
        else ClaimResult.Deny("The world is not old enough for that yet.")
    }

    /**
     * Only the spirit at the front of the queue may take it.
     *
     * A singleton on purpose: core needs to *recognise* this rule (to waive it for the
     * spirit currently holding an offer, who got there because of it) and it does that
     * by identity, not by string-matching the deny message.
     */
    val QUEUE_PRIORITY: ClaimRule = ClaimRule { _, ctx ->
        if (ctx.queuePosition <= 0) ClaimResult.Allow
        else ClaimResult.Deny("Other spirits wait ahead of you (you are #${ctx.queuePosition + 1}).")
    }

    fun queuePriority(): ClaimRule = QUEUE_PRIORITY
}

/**
 * A role is a *definition*, registered once by a story addon. Core owns who holds
 * it, persists that across restarts, and hands it on when it falls vacant.
 */
data class RoleDefinition(
    /** Lowercase, unique, stable — this is what gets written to disk. e.g. "gaia". */
    val id: String,
    val displayName: String,
    val tier: RoleTier,
    /** The era that introduces this role. Used for `/roles` grouping and gating. */
    val era: String,
    /** Flavour tags — "earth", "sky", "night". Addons can key mechanics off these. */
    val domains: List<String> = emptyList(),
    /** How many may hold it at once. Zeus: 1. Nymph: 30. */
    val maxHolders: Int = 1,
    /** MiniMessage colour for names, titles and broadcasts. */
    val color: String = "<gold>",
    val lore: List<String> = emptyList(),
    /** Ids of powers (registered with PowerService) granted while holding this role. */
    val powers: List<String> = emptyList(),
    /** ALL must allow before a claim succeeds. Empty = anyone, first come. */
    val claimRules: List<ClaimRule> = emptyList(),
    val succession: Succession = Succession.QUEUE,
    /** Does this role outlive its own era? See [Endurance]. */
    val endurance: Endurance = Endurance.ETERNAL,
    /** Sealed roles are hidden and unclaimable until an addon calls `open()`. */
    val startsSealed: Boolean = false,
)

/** Core's registry of who is what. Thread-safe — call it from any region. */
interface RoleService {

    fun register(definition: RoleDefinition)

    /**
     * Change a role you don't own, without editing the addon that does.
     *
     * ```kotlin
     * // In Titanomachy: Gaia has been through this before, and she has opinions.
     * mythos.roles.extend("gaia") { it.copy(powers = it.powers + "prophesy") }
     * ```
     *
     * Holders keep the role; they simply gain whatever you added. Returns false if no
     * such role is registered (the addon that owns it isn't installed) — which is not an
     * error, just a story that isn't being told on this server.
     */
    fun extend(roleId: String, transform: (RoleDefinition) -> RoleDefinition): Boolean

    fun definition(roleId: String): RoleDefinition?
    fun definitions(): List<RoleDefinition>
    fun definitionsInEra(eraId: String): List<RoleDefinition>

    fun holders(roleId: String): Set<UUID>
    fun roleOf(uuid: UUID): RoleDefinition?
    fun isSealed(roleId: String): Boolean
    fun isOpen(roleId: String): Boolean

    /**
     * Every role with a free seat. Note that a seat being free does NOT mean anyone
     * can sit in it — the Titans always have free seats, and nobody may ever claim one.
     * For anything player-facing you almost certainly want [claimableBy].
     */
    fun openRoles(): List<RoleDefinition>

    /**
     * Dry run: what would `claim` say? No side effects, no events, no essence spent.
     * Use it to explain *why* a mantle is closed to someone instead of just refusing.
     */
    fun evaluate(player: Player, roleId: String): ClaimResult

    /**
     * The roles this player could actually take, right now, if they typed `/claim`.
     * Every gate evaluated against them. This is what `/claim` lists, what `/spirit`
     * shows, and the only thing core will ever advertise — never advertise a mantle
     * and then refuse it.
     */
    fun claimableBy(player: Player): List<RoleDefinition>

    /**
     * Run every gate, then bind the player to the role. Fires RoleClaimedEvent
     * (cancellable) and lifts them out of spirit form on success.
     */
    fun claim(player: Player, roleId: String): ClaimResult

    /** Bypass every gate — for admins, and for stories that *choose* their cast. */
    fun assign(uuid: UUID, roleId: String, reason: String)

    /** Strip the role; the player drops back into the spirit world. */
    fun release(uuid: UUID, reason: String)

    fun seal(roleId: String, reason: String)
    fun open(roleId: String, reason: String)

    fun setHeir(holder: UUID, heir: UUID?)
    fun heirOf(holder: UUID): UUID?
}
