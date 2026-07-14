package net.crewco.mythos.engine

import net.crewco.mythos.api.event.RoleClaimEvent
import net.crewco.mythos.api.event.RoleClaimedEvent
import net.crewco.mythos.api.event.RoleReleasedEvent
import net.crewco.mythos.api.role.ClaimContext
import net.crewco.mythos.api.role.ClaimResult
import net.crewco.mythos.api.era.EraDefinition
import net.crewco.mythos.api.event.RoleRetiringEvent
import net.crewco.mythos.api.role.ClaimRules
import net.crewco.mythos.api.role.Endurance
import net.crewco.mythos.api.role.RoleDefinition
import net.crewco.mythos.api.role.RoleService
import net.crewco.mythos.api.role.RoleTier
import net.crewco.mythos.api.role.Succession
import net.crewco.mythos.command.CommandContext.Companion.mm
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffectType
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Who is who.
 *
 * Claiming is the one place in this plugin where a race between two region threads
 * would actually matter (two players typing `/claim zeus` on the same tick, on
 * different threads, into a one-seat role), so the check-and-take is done inside a
 * single `synchronized` block. Everything after that — the visuals, the title, the
 * broadcast — is scheduled onto the region that owns the player.
 */
class RoleServiceImpl(private val core: MythosEngine) : RoleService {

    private val definitions = ConcurrentHashMap<String, RoleDefinition>()
    private val holders = ConcurrentHashMap<String, MutableSet<UUID>>()
    private val sealedRoles = CopyOnWriteArraySet<String>()
    private val heirs = ConcurrentHashMap<UUID, UUID>()

    /** uuid → epoch millis when they may claim again (set on abdication/deposition). */
    private val claimCooldowns = ConcurrentHashMap<UUID, Long>()

    private val lock = Any()

    override fun register(definition: RoleDefinition) {
        val id = definition.id.lowercase()
        definitions[id] = definition.copy(id = id)
        holders.putIfAbsent(id, Collections.newSetFromMap(ConcurrentHashMap()))
        if (definition.startsSealed) sealedRoles += id
        core.logger.info("Registered role '${id}' (${definition.tier}, ${definition.maxHolders} seat(s))")
    }

    /**
     * Change a role you don't own. The holder keeps it and simply gains whatever you
     * added — `register` overwrites the definition by id, and holders are keyed by id,
     * not by object.
     */
    override fun extend(roleId: String, transform: (RoleDefinition) -> RoleDefinition): Boolean {
        val id = roleId.lowercase()
        val existing = definitions[id] ?: return false // its addon isn't installed. Fine.
        val extended = transform(existing).copy(id = id)
        definitions[id] = extended
        core.logger.info("Role '$id' extended (powers: ${extended.powers.joinToString().ifEmpty { "none" }})")
        return true
    }

    override fun definition(roleId: String) = definitions[roleId.lowercase()]
    override fun definitions() = definitions.values.sortedWith(compareBy({ it.tier.ordinal }, { it.displayName }))
    override fun definitionsInEra(eraId: String) = definitions.values.filter { it.era == eraId }

    override fun holders(roleId: String): Set<UUID> = holders[roleId.lowercase()]?.toSet() ?: emptySet()

    override fun roleOf(uuid: UUID): RoleDefinition? =
        core.profiles.profile(uuid).roleId?.let { definitions[it] }

    override fun isSealed(roleId: String) = roleId.lowercase() in sealedRoles

    override fun isOpen(roleId: String): Boolean {
        val id = roleId.lowercase()
        val definition = definitions[id] ?: return false
        if (id in sealedRoles) return false
        return (holders[id]?.size ?: 0) < definition.maxHolders
    }

    override fun openRoles(): List<RoleDefinition> = definitions().filter { isOpen(it.id) }

    /** The context every gate is judged against. One place, so claim/evaluate/offer agree. */
    private fun context(player: Player, roleId: String) = ClaimContext(
        roleId = roleId,
        currentEra = core.eras.currentId(),
        pastEras = core.eras.snapshotPassed().toSet(),
        holders = holders(roleId),
        essence = core.profiles.profile(player.uniqueId).essence,
        queuePosition = core.spirits.queuePosition(player.uniqueId),
    )

    /**
     * Every gate, in order, with no side effects.
     *
     * [waiveQueue] is for the spirit who is holding an offer for this role: the queue
     * rule is the reason they were offered it, so it must not then veto them. Matched
     * by identity — ClaimRules.QUEUE_PRIORITY is a singleton precisely so this doesn't
     * have to string-match a deny message.
     */
    private fun gates(player: Player, definition: RoleDefinition, waiveQueue: Boolean): ClaimResult {
        // The world is between ages. Let the scene land.
        if (core.eras.isTransitioning) return ClaimResult.Deny("The world is between ages. Wait — it always starts again.")
        if (isSealed(definition.id)) return ClaimResult.Deny("${definition.displayName} is sealed away.")
        if (!isOpen(definition.id)) return ClaimResult.Deny("${definition.displayName} is already taken.")

        // Solo mode: an admin skips every gate below this line. Not the seat count, and
        // not the seal — a bypass that lets you break the *engine's* invariants is a
        // bypass that lets you file bugs against yourself.
        if (core.dev.bypasses(player)) return ClaimResult.Allow
        if (core.config.requirePermission && !player.hasPermission("mythos.claim.${definition.id}")) {
            return ClaimResult.Deny("The Fates have not written your name for ${definition.displayName}.")
        }
        val ctx = context(player, definition.id)
        for (rule in definition.claimRules) {
            if (waiveQueue && rule === ClaimRules.QUEUE_PRIORITY) continue
            val result = rule.evaluate(player, ctx)
            if (result is ClaimResult.Deny) return result
        }
        return ClaimResult.Allow
    }

    override fun evaluate(player: Player, roleId: String): ClaimResult {
        val definition = definitions[roleId.lowercase()] ?: return ClaimResult.Deny("No such role.")
        val holdsOffer = core.spirits.pendingOffer(player.uniqueId) == definition.id
        return gates(player, definition, waiveQueue = holdsOffer)
    }

    /**
     * What this player could actually take. NEVER advertise anything else: a Titan
     * always has a free seat and can never be claimed (Gaia bears them), and listing it
     * as "available" only teaches players that /claim lies to them.
     */
    override fun claimableBy(player: Player): List<RoleDefinition> =
        openRoles().filter { evaluate(player, it.id) is ClaimResult.Allow }

    // ---- claiming -----------------------------------------------------------

    override fun claim(player: Player, roleId: String): ClaimResult {
        val id = roleId.lowercase()
        val definition = definitions[id] ?: return ClaimResult.Deny("No such role: $roleId")
        val uuid = player.uniqueId
        val profile = core.profiles.profile(uuid)

        if (id in sealedRoles) return ClaimResult.Deny("${definition.displayName} is sealed away.")
        if (profile.roleId != null) {
            return ClaimResult.Deny("You already walk as ${definitions[profile.roleId]?.displayName ?: profile.roleId}. Abdicate first: /role abdicate")
        }

        val cooldownUntil = if (core.dev.bypasses(player)) 0L else claimCooldowns[uuid] ?: 0L
        if (System.currentTimeMillis() < cooldownUntil) {
            val seconds = (cooldownUntil - System.currentTimeMillis()) / 1000
            return ClaimResult.Deny("The Fates are not done with your last life. ($seconds s)")
        }

        // An offer outranks everything: if this role was promised to another spirit,
        // nobody else may snipe it out from under them.
        val promisedTo = core.spirits.offerHolderOf(id)
        if (core.config.queuePriority && definition.maxHolders == 1 && promisedTo != null && promisedTo != uuid) {
            return ClaimResult.Deny("${definition.displayName} has been offered to another spirit. Wait for their answer.")
        }

        // Every gate, exactly as `/claim` previewed them. A spirit holding an offer for
        // this role is waived past the queue rule — the queue is what handed it to them.
        val verdict = gates(player, definition, waiveQueue = promisedTo == uuid)
        if (verdict is ClaimResult.Deny) return verdict

        val cost = if (core.dev.bypasses(player)) 0 else core.config.essenceCost(definition.tier)

        // The one true race: check the seat and take it, atomically.
        synchronized(lock) {
            val seat = holders.getOrPut(id) { Collections.newSetFromMap(ConcurrentHashMap()) }
            if (seat.size >= definition.maxHolders) {
                return ClaimResult.Deny("${definition.displayName} is already taken.")
            }
            if (cost > 0 && !core.spirits.spendEssence(uuid, cost)) {
                return ClaimResult.Deny("You need $cost essence; you have ${profile.essence}.")
            }

            val event = RoleClaimEvent(player, definition, forced = false)
            Bukkit.getPluginManager().callEvent(event)
            if (event.isCancelled) {
                if (cost > 0) core.spirits.grantEssence(uuid, cost, "refund")
                return ClaimResult.Deny(event.denyReason)
            }
            seat += uuid
        }

        bind(player, definition)
        return ClaimResult.Allow
    }

    override fun assign(uuid: UUID, roleId: String, reason: String) {
        val id = roleId.lowercase()
        val definition = definitions[id] ?: return
        synchronized(lock) {
            holders.getOrPut(id) { Collections.newSetFromMap(ConcurrentHashMap()) } += uuid
        }
        val player = Bukkit.getPlayer(uuid)
        if (player != null) {
            Bukkit.getPluginManager().callEvent(RoleClaimEvent(player, definition, forced = true))
            bind(player, definition)
        } else {
            // Offline: write it down; they'll wake up as a god.
            core.profiles.profile(uuid).roleId = id
            core.saveState()
        }
        core.logger.info("Assigned '$id' to $uuid ($reason)")
    }

    /** Write the role into the profile, then dress the player in it. */
    private fun bind(player: Player, definition: RoleDefinition) {
        val profile = core.profiles.profile(player.uniqueId)
        profile.roleId = definition.id
        core.spirits.dequeue(player.uniqueId)
        core.spirits.revokeOffer(player.uniqueId)
        core.saveState()
        core.schedulers.async { core.profiles.save(player.uniqueId) }

        core.schedulers.entity(player) {
            core.spirits.embody(player)
            applyBody(player, definition)

            player.showTitle(
                Title.title(
                    mm("${definition.color}${definition.displayName}"),
                    mm("<gray>${definition.tier.displayName} of ${definition.domains.joinToString(", ").ifBlank { "the myth" }}"),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofMillis(800)),
                ),
            )
            definition.lore.forEach { player.sendMessage(mm("<dark_gray>| <gray><i>$it")) }

            val powers = definition.powers.mapNotNull { core.powers.power(it) }
            if (powers.isNotEmpty()) {
                player.sendMessage(mm("<dark_gray>| <gray>Your powers: <white>${powers.joinToString { it.displayName }} <dark_gray>(/power)"))
            }

            Bukkit.getPluginManager().callEvent(RoleClaimedEvent(player, definition))
        }

        if (core.config.broadcastClaims) {
            Bukkit.getServer().sendMessage(
                mm("<dark_gray>» ${definition.color}<b>${definition.displayName}</b> <gray>walks the world again — <white>${player.name}<gray>."),
            )
        }
        core.chronicle.record("claim", "<white>${player.name} <gray>took up ${definition.color}${definition.displayName}<gray>.")
    }

    /** Stats, flight, health. A Primordial should *feel* like one. */
    fun applyBody(player: Player, definition: RoleDefinition) {
        player.gameMode = GameMode.SURVIVAL
        // 1.21.4+ dropped the GENERIC_ prefix on attribute constants.
        player.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 20.0 + definition.tier.heartsBonus * 2.0
        player.health = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        player.removePotionEffect(PotionEffectType.INVISIBILITY)
        player.isCollidable = true
        player.isInvulnerable = false

        // Gods walk on air.
        val divine = definition.tier in setOf(
            RoleTier.PRIMORDIAL, RoleTier.TITAN, RoleTier.OLYMPIAN, RoleTier.CHTHONIC,
        )
        player.allowFlight = divine
        if (!divine) player.isFlying = false

        if (core.config.prefixNames) {
            player.displayName(mm("${definition.color}${player.name}"))
            player.playerListName(mm("${definition.color}${player.name}"))
        }
    }

    // ---- losing a mantle ----------------------------------------------------

    override fun release(uuid: UUID, reason: String, quiet: Boolean) =
        release(uuid, reason, quiet = quiet, cooldown = !quiet)

    /**
     * @param quiet    no server-wide broadcast (retiring a 60-strong army would be 60 lines)
     * @param cooldown impose the "the Fates aren't done with you" claim cooldown. False when
     *                 the age simply ended — that's not their fault, and the next story wants them.
     */
    private fun release(uuid: UUID, reason: String, quiet: Boolean, cooldown: Boolean) {
        val profile = core.profiles.impl(uuid)
        val id = profile.roleId ?: return
        val definition = definitions[id] ?: return

        synchronized(lock) { holders[id]?.remove(uuid) }
        profile.roleId = null
        profile.recordPastRole(id)
        heirs.remove(uuid)
        if (cooldown) claimCooldowns[uuid] = System.currentTimeMillis() + core.config.claimCooldownSeconds * 1000
        core.saveState()
        core.schedulers.async { core.profiles.save(uuid) }

        val player = Bukkit.getPlayer(uuid)
        Bukkit.getPluginManager().callEvent(RoleReleasedEvent(uuid, definition, reason, player))

        if (!quiet) {
            Bukkit.getServer().sendMessage(
                mm("<dark_gray>» ${definition.color}${definition.displayName} <gray>is no more — <dark_gray><i>$reason"),
            )
            core.spirits.grantEssenceToAll(core.config.essenceOnDivineDeath, "witnessed the fall of ${definition.displayName}")
            core.chronicle.record(
                "death",
                "${definition.color}${definition.displayName} <gray>fell — <i>$reason</i> <dark_gray>(${profile.name})",
            )
        }

        if (player != null) {
            core.schedulers.entity(player) { core.spirits.makeSpirit(player, "you have lost ${definition.displayName}") }
        }

        /*
         * Reincarnation.
         *
         * With permadeath on, a mortal who dies loses the role and drops into the spirit
         * world — where they'd sit, bodiless, until they logged out and back in and the
         * join handler put them back in a body. On a hundred-player server that is a
         * catastrophe, and it took writing actual mortals to notice it.
         *
         * So: if the role you just lost IS the default role, you get another one. Five
         * seconds as a ghost, then a new body. A mortal dies and a mortal is born.
         *
         * A GOD who dies gets nothing of the kind. That asymmetry is the entire point.
         */
        val fallback = core.config.defaultRole
        if (!quiet && fallback.isNotEmpty() && fallback == id) {
            core.schedulers.globalDelayed(100) {
                if (isOpen(fallback) && core.profiles.profile(uuid).roleId == null) {
                    claimCooldowns.remove(uuid) // dying is not a cooldown offence
                    assign(uuid, fallback, "born again")
                    Bukkit.getPlayer(uuid)?.sendMessage(
                        mm("<gray>You wake up somewhere else, as someone else. <dark_gray><i>The world does not comment."),
                    )
                }
            }
        }

        // Who takes it up?
        when (definition.succession) {
            Succession.CLOSED -> seal(id, "sealed by its own nature")
            Succession.HEIR -> {
                val heir = heirs[uuid]
                if (heir != null) assign(heir, id, "named heir of ${definition.displayName}")
                else core.spirits.offerToQueue(id)
            }
            Succession.QUEUE -> core.spirits.offerToQueue(id)
        }
    }

    override fun seal(roleId: String, reason: String) {
        val id = roleId.lowercase()
        sealedRoles += id
        core.spirits.revokeOffersFor(id)
        holders[id]?.toList()?.forEach { release(it, reason) }
        core.saveState()
    }

    override fun open(roleId: String, reason: String) {
        val id = roleId.lowercase()
        if (!sealedRoles.remove(id)) return
        core.saveState()
        definitions[id]?.let {
            Bukkit.getServer().sendMessage(mm("<dark_gray>» ${it.color}${it.displayName} <gray>stirs — the seat is open. <dark_gray>($reason)"))
            core.spirits.offerToQueue(id)
        }
    }

    override fun setHeir(holder: UUID, heir: UUID?) {
        if (heir == null) heirs.remove(holder) else heirs[holder] = heir
        core.saveState()
    }

    override fun heirOf(holder: UUID): UUID? = heirs[holder]

    /**
     * The age has turned. Everyone whose story just ended goes back to the spirit world.
     *
     * This is what stops the server accumulating a cast of characters from stories
     * nobody is telling any more — the Titan-sworn army still marching around three
     * eras after the war it was raised for.
     *
     * Roles are dissolved only if they declared [Endurance.ERA]. The gods (ETERNAL) are
     * untouched: the Earth does not stop existing because we've moved on to Troy. And a
     * downstream addon can keep any individual on stage by cancelling RoleRetiringEvent,
     * which is how one myth inherits a character from another.
     */
    fun retireCast(from: EraDefinition?, to: EraDefinition) {
        if (!core.config.retireCastOnAdvance) return

        val retired = ArrayList<Pair<String, RoleDefinition>>()

        definitions()
            .filter { it.endurance == Endurance.ERA && it.era != to.id }
            .forEach { definition ->
                val kept = ArrayList<UUID>()

                holders(definition.id).forEach { uuid ->
                    val event = RoleRetiringEvent(uuid, definition, from?.id, to.id, Bukkit.getPlayer(uuid))
                    Bukkit.getPluginManager().callEvent(event)
                    if (event.isCancelled) {
                        kept += uuid // a later myth wants this character. Let them walk into it.
                        return@forEach
                    }

                    val profile = core.profiles.impl(uuid)
                    profile.titles += "Once ${definition.displayName}"
                    val name = profile.name

                    release(uuid, "the age ended", quiet = true, cooldown = false)
                    core.spirits.grantEssence(
                        uuid,
                        core.config.retirementEssence + definition.tier.heartsBonus,
                        "your part in the story is played",
                    )
                    retired += name to definition
                }

                // Nobody left wearing it, and its age is over: seal it. An addon that
                // wants it back calls roles.open(id).
                if (kept.isEmpty() && holders(definition.id).isEmpty()) {
                    sealedRoles += definition.id
                    core.spirits.revokeOffersFor(definition.id)
                }
            }

        core.saveState()
        if (retired.isEmpty()) return

        Bukkit.getServer().sendMessage(mm(""))
        Bukkit.getServer().sendMessage(
            mm("<dark_gray>» <gray>The age turns, and <white>${retired.size}</white> step out of the story:"),
        )
        retired.take(12).forEach { (name, role) ->
            Bukkit.getServer().sendMessage(mm("<dark_gray>   $name <dark_gray>— once ${role.color}${role.displayName}"))
        }
        if (retired.size > 12) Bukkit.getServer().sendMessage(mm("<dark_gray>   ...and ${retired.size - 12} more."))
        Bukkit.getServer().sendMessage(mm("<dark_gray><i>   They return to the spirit world, richer for it, and first in line for what comes next."))
        Bukkit.getServer().sendMessage(mm(""))
        core.chronicle.record(
            "era",
            "<gray>${retired.size} left the story when the ${from?.displayName ?: "age"} ended.",
        )
    }

    /** Throttle: the world is allowed to shout about vacancies once a minute, not once per role per join. */
    private val lastAnnounce = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Tell the spirit world what's going begging — ONCE, in one line, and only to
     * spirits who could actually take something.
     *
     * This used to fire from every failed offer attempt, for every open role, on every
     * join. Eight vacancies and ninety spirits meant a wall of text that also happened
     * to be advertising roles nobody was permitted to claim. Now: throttled, deduped,
     * and filtered per-player.
     */
    fun announceOpenRoles(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val previous = lastAnnounce.get()
        if (!force && now - previous < ANNOUNCE_COOLDOWN_MS) return
        if (!lastAnnounce.compareAndSet(previous, now)) return // another thread just did it

        core.spirits.spirits().mapNotNull { Bukkit.getPlayer(it) }.forEach { spirit ->
            core.schedulers.entity(spirit) {
                val theirs = claimableBy(spirit)
                if (theirs.isEmpty()) return@entity
                spirit.sendMessage(
                    mm("<gray>Yours for the taking: ${theirs.joinToString("<gray>, ") { "${it.color}${it.displayName}" }} <dark_gray>— /claim"),
                )
            }
        }
    }

    // ---- persistence --------------------------------------------------------

    internal fun load(
        savedHolders: Map<String, List<UUID>>,
        savedSealed: Collection<String>,
        savedHeirs: Map<UUID, UUID>,
    ) {
        savedHolders.forEach { (roleId, uuids) ->
            holders.getOrPut(roleId) { Collections.newSetFromMap(ConcurrentHashMap()) } += uuids
        }
        sealedRoles += savedSealed
        heirs += savedHeirs
    }

    internal fun snapshotHolders(): Map<String, List<UUID>> = holders.mapValues { it.value.toList() }
    internal fun snapshotSealed(): List<String> = sealedRoles.toList()
    internal fun snapshotHeirs(): Map<UUID, UUID> = heirs.toMap()

    /** Wipe every mantle. Nobody is anybody. */
    internal fun resetAll() {
        synchronized(lock) {
            holders.values.forEach { it.clear() }
            sealedRoles.clear()
            heirs.clear()
            claimCooldowns.clear()
        }
        lastAnnounce.set(0)
    }

    private companion object {
        const val ANNOUNCE_COOLDOWN_MS = 60_000L
    }
}
