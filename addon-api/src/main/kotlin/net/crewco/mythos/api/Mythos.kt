package net.crewco.mythos.api

import net.crewco.mythos.addon.AddonContext
import net.crewco.mythos.addon.service
import net.crewco.mythos.api.era.EraService
import net.crewco.mythos.api.ext.ExtensionService
import net.crewco.mythos.api.power.PowerService
import net.crewco.mythos.api.profile.ProfileService
import net.crewco.mythos.api.role.RoleService
import net.crewco.mythos.api.spirit.SpiritService
import net.crewco.mythos.api.story.ChronicleService
import net.crewco.mythos.api.story.NarratorService

/**
 * The five systems the **Mythos plugin** publishes. This is the whole engine, as far
 * as a story addon is concerned.
 *
 * ```kotlin
 * class TrojanWarAddon : AddonBase() {
 *     override fun onEnable() {
 *         val mythos = Mythos.from(context)
 *
 *         mythos.eras.register(TROJAN_WAR)      // declares the era that FOLLOWS it, too
 *         mythos.roles.register(ACHILLES)
 *         mythos.powers.register(RagePower())
 *         context.registerListener(WarListener(mythos))
 *     }
 * }
 * ```
 *
 * **No `depends:` required.** The engine is the plugin, not an addon — the host boots
 * it before it loads a single jar, so these services are already registered by the time
 * your onEnable() runs. An addon only declares `depends:` when it needs *another story*.
 */
class Mythos(
    /** Who is who: definitions, claiming, succession, retirement. */
    val roles: RoleService,

    /** Everyone without a name: the queue, offers, essence. */
    val spirits: SpiritService,

    /** The clock of the world: eras, objectives, and what follows what. */
    val eras: EraService,

    /** What a role lets you do. */
    val powers: PowerService,

    /** What the world remembers about a player, across every age. */
    val profiles: ProfileService,

    /** Stages a scene: timed beats, titles, sounds. Use it instead of broadcasting. */
    val narrator: NarratorService,

    /** The history of the world, written as it happens, kept forever. */
    val chronicle: ChronicleService,

    /** How one story reaches into another. Load order doesn't matter. */
    val extensions: ExtensionService,
) {
    companion object {
        fun from(context: AddonContext): Mythos = Mythos(
            roles = context.service<RoleService>() ?: missing("RoleService"),
            spirits = context.service<SpiritService>() ?: missing("SpiritService"),
            eras = context.service<EraService>() ?: missing("EraService"),
            powers = context.service<PowerService>() ?: missing("PowerService"),
            profiles = context.service<ProfileService>() ?: missing("ProfileService"),
            narrator = context.service<NarratorService>() ?: missing("NarratorService"),
            chronicle = context.service<ChronicleService>() ?: missing("ChronicleService"),
            extensions = context.service<ExtensionService>() ?: missing("ExtensionService"),
        )

        private fun missing(what: String): Nothing = error(
            "$what is unavailable. The Mythos engine failed to start — check the server log " +
                "for an error from the plugin itself, above the addon load lines.",
        )
    }
}
