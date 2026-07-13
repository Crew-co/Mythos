package net.crewco.mythos.engine

import net.crewco.mythos.api.era.EraDefinition
import net.crewco.mythos.api.era.EraService
import net.crewco.mythos.api.era.Objective
import net.crewco.mythos.api.event.EraAdvancedEvent
import net.crewco.mythos.api.event.ObjectiveCompletedEvent
import net.crewco.mythos.api.story.Beat
import net.crewco.mythos.command.CommandContext.Companion.mm
import org.bukkit.Bukkit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The clock of the world — and the stage manager.
 *
 * Two things make the stories *flow* rather than jump-cut:
 *
 *  1. **The chain is data, not code.** An era declares who follows it, but that's a
 *     default: [insertAfter] rewires the pointers, so a chapter written years later can
 *     be spliced between two existing ones without either of them knowing.
 *
 *  2. **A transition is a scene, not a broadcast.** The old era's epilogue plays out,
 *     the world goes dark and holds still — nothing can be claimed — and only then does
 *     the next era's prologue begin. A story gets to finish before the next one starts.
 *
 * Every mutation is funnelled onto the global region: era state is shared by all regions
 * and Folia gives you no main thread to fall back on.
 */
class EraServiceImpl(private val core: MythosEngine) : EraService {

    private val eras = ConcurrentHashMap<String, EraDefinition>()
    private val completed = CopyOnWriteArraySet<String>() // "eraId:objectiveId"
    private val passed = CopyOnWriteArraySet<String>()

    /** Rewired `next` pointers, from insertAfter/insertBefore. Beats the declared one. */
    private val chainOverrides = ConcurrentHashMap<String, String>()

    /** Objectives other addons bolted onto someone else's chapter. */
    private val extraObjectives = ConcurrentHashMap<String, CopyOnWriteArraySet<Objective>>()

    @Volatile
    private var current: String = ""

    @Volatile
    private var transitioning: Boolean = false

    override val isTransitioning: Boolean get() = transitioning

    override fun register(era: EraDefinition) {
        eras[era.id] = era
        core.logger.info("Era '${era.id}' registered (#${era.order} → ${era.next ?: "the end"})")
    }

    override fun current(): EraDefinition? = eras[current]
    override fun currentId(): String = current
    override fun era(id: String) = eras[id]
    override fun eras(): List<EraDefinition> = eras.values.sortedBy { it.order }
    override fun isPast(eraId: String) = eraId in passed

    // ---- the chain -----------------------------------------------------------

    override fun nextOf(eraId: String): String? = chainOverrides[eraId] ?: eras[eraId]?.next

    override fun insertAfter(afterEraId: String, era: EraDefinition) {
        val after = eras[afterEraId] ?: run {
            // The era it wanted to follow isn't installed. Register anyway — it's still a
            // valid chapter, just an orphan on this server. Not an error: a story that
            // isn't being told.
            core.logger.info("'${era.id}' wanted to follow '$afterEraId', which isn't installed. Registered unlinked.")
            register(era)
            return
        }
        register(era)
        val displaced = nextOf(after.id)      // whatever used to come next...
        chainOverrides[after.id] = era.id     // ...now comes after us...
        displaced?.let { chainOverrides[era.id] = it } // ...and we point at it.
        core.logger.info("Spliced '${era.id}' between '${after.id}' and '${displaced ?: "the end"}'")
    }

    override fun insertBefore(beforeEraId: String, era: EraDefinition) {
        register(era)
        chainOverrides[era.id] = beforeEraId
        val predecessor = eras.values.firstOrNull { nextOf(it.id) == beforeEraId && it.id != era.id }
        if (predecessor != null) {
            chainOverrides[predecessor.id] = era.id
            core.logger.info("Spliced '${era.id}' between '${predecessor.id}' and '$beforeEraId'")
        } else {
            // Nothing pointed at it — so this is the new opening chapter. Give it a lower
            // `order` than the era it precedes, or bootstrap won't pick it as the start.
            core.logger.info("Spliced '${era.id}' in front of '$beforeEraId' as the new beginning")
        }
    }

    override fun chain(): List<EraDefinition> {
        val start = eras.values.minByOrNull { it.order } ?: return emptyList()
        val walked = LinkedHashSet<String>()
        var cursor: String? = start.id
        while (cursor != null && walked.add(cursor)) {
            cursor = nextOf(cursor)
        }
        val linked = walked.mapNotNull { eras[it] }
        val orphans = eras.values.filterNot { it.id in walked }.sortedBy { it.order }
        return linked + orphans
    }

    // ---- objectives ----------------------------------------------------------

    override fun objectives(eraId: String): List<Objective> =
        eras[eraId]?.objectives.orEmpty() + extraObjectives[eraId].orEmpty()

    override fun addObjective(eraId: String, objective: Objective) {
        extraObjectives.getOrPut(eraId) { CopyOnWriteArraySet() } += objective
        core.logger.info("Objective '${objective.id}' added to era '$eraId' by an extension")
    }

    override fun isComplete(eraId: String, objectiveId: String) = "$eraId:$objectiveId" in completed

    override fun complete(eraId: String, objectiveId: String, reason: String) {
        core.schedulers.global {
            val era = eras[eraId] ?: return@global
            val objective = objectives(eraId).firstOrNull { it.id == objectiveId } ?: return@global
            if (!completed.add("$eraId:$objectiveId")) return@global // already struck

            Bukkit.getPluginManager().callEvent(ObjectiveCompletedEvent(eraId, objectiveId, reason))
            Bukkit.getServer().sendMessage(mm("<dark_gray>» <gray><i>${objective.description}</i> <dark_gray>— it is done."))
            core.chronicle.record("objective", objective.description)
            core.saveState()

            val remaining = objectives(eraId).count { !it.optional && "$eraId:${it.id}" !in completed }
            if (remaining == 0 && current == eraId) {
                val next = nextOf(eraId)
                if (next == null || !advance(next, "the ${era.displayName} ran its course")) {
                    Bukkit.getServer().sendMessage(
                        mm("<gold>The ${era.displayName} is complete <gray>— and nothing has been written to follow it."),
                    )
                    if (next != null) core.logger.warning("Era '$eraId' points at '$next', which no addon registered.")
                }
            }
        }
    }

    // ---- the turning of an age ------------------------------------------------

    override fun advance(toEraId: String, reason: String): Boolean {
        val target = eras[toEraId] ?: run {
            core.logger.warning("Cannot advance to unknown era '$toEraId' (is its addon installed?)")
            return false
        }
        if (transitioning) {
            core.logger.warning("Already between ages — refusing to advance to '$toEraId'")
            return false
        }

        core.schedulers.global {
            val from = eras[current]
            transitioning = true

            // 1. The curtain comes down. The old story finishes speaking.
            val epilogue = from?.epilogue.orEmpty()
            val epilogueLength = if (epilogue.isEmpty()) 0 else core.narrator.tell(epilogue)

            // 2. Dark, and quiet, and the world holds still.
            val hold = core.config.interludeTicks
            core.schedulers.globalDelayed(epilogueLength + hold) {
                core.narrator.darkenEveryone(60)

                if (from != null) {
                    passed += from.id
                    core.chronicle.record("era", "<gray>The ${from.displayName} ended — <i>$reason")
                }
                current = target.id
                core.saveState()

                Bukkit.getPluginManager().callEvent(EraAdvancedEvent(from, target, reason))
                core.chronicle.record("era", "<gold>The ${target.displayName} began.")

                // 3. The curtain goes up on the next one.
                val opening = openingBeats(target)
                val openingLength = core.narrator.tell(opening)

                core.spirits.grantEssenceToAll(core.config.essenceOnEraAdvance, "the age turned")

                // AFTER EraAdvancedEvent, so the incoming addon has had its say — and can
                // have cancelled the retirement of anyone it wants to keep on stage.
                core.roles.retireCast(from, target)

                core.schedulers.globalDelayed(openingLength + 20) {
                    transitioning = false
                    core.roles.announceOpenRoles(force = true)
                }
            }
        }
        return true
    }

    /** A title card, then whatever the era wanted to say. Falls back to its lore. */
    private fun openingBeats(era: EraDefinition): List<Beat> {
        val card = listOf(
            Beat(
                delayTicks = 20,
                title = "<gold>${era.displayName}",
                subtitle = "<gray>${era.subtitle}",
                sound = "minecraft:block.beacon.activate",
            ),
        )
        val body = era.prologue.ifEmpty {
            era.lore.map { Beat(delayTicks = 45, text = "<gray><i>$it") }
        }
        return card + body
    }

    // ---- persistence ----------------------------------------------------------

    internal fun load(currentId: String, done: Collection<String>, past: Collection<String>) {
        current = currentId
        completed += done
        passed += past
    }

    internal fun snapshotCurrent() = current
    internal fun snapshotCompleted() = completed.toList()
    internal fun snapshotPassed() = passed.toList()

    /** One tick after startup, when every addon has registered its chapter. */
    internal fun bootstrap() {
        if (current.isNotEmpty() && eras.contains(current)) {
            core.logger.info("Resuming the age of '$current'.")
            return
        }
        val first = chain().firstOrNull() ?: run {
            core.logger.warning("No eras registered — install a story addon (e.g. EraOfCreation).")
            return
        }
        advance(first.id, "the world begins")
    }
}
