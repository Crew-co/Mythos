package net.crewco.mythos.engine

import net.crewco.mythos.api.role.RoleTier
import org.bukkit.GameMode
import org.bukkit.configuration.file.FileConfiguration

/** Typed view over config.yml. Re-read on `/addons reload`. */
class CoreConfig(private val cfg: FileConfiguration) {

    val requirePermission = cfg.getBoolean("claiming.require-permission", false)
    val queuePriority = cfg.getBoolean("claiming.queue-priority", true)
    val offerSeconds = cfg.getInt("claiming.offer-seconds", 60)
    val claimCooldownSeconds = cfg.getLong("claiming.cooldown-seconds", 300)

    /**
     * On a 100+ server most players should never be spirits — being nobody is a
     * *state between roles*, not the default condition. Set this to a many-seated
     * role (once one exists: "mortal", "olympian-sworn") and joiners land in it.
     * Empty = everyone starts as a spirit, which is correct for the Age of Chaos,
     * because in the Age of Chaos there is nothing to be.
     */
    val defaultRole: String = cfg.getString("claiming.default-role", "")!!.lowercase()

    val spiritMode: GameMode =
        runCatching { GameMode.valueOf(cfg.getString("spirit.mode", "ADVENTURE")!!.uppercase()) }
            .getOrDefault(GameMode.ADVENTURE)
    val spiritInvisible = cfg.getBoolean("spirit.invisible", true)
    val spiritCanFly = cfg.getBoolean("spirit.can-fly", true)
    val essencePerInterval = cfg.getInt("spirit.essence-per-interval", 1)
    val essenceIntervalMinutes = cfg.getLong("spirit.interval-minutes", 5)
    val essenceOnEraAdvance = cfg.getInt("spirit.essence-on-era-advance", 10)
    val essenceOnDivineDeath = cfg.getInt("spirit.essence-on-divine-death", 5)

    /** RELEASE or KEEP. */
    val releaseOnDivineDeath = cfg.getString("death.on-divine-death", "RELEASE")!!.equals("RELEASE", true)
    val releaseAfterOfflineHours = cfg.getLong("death.release-after-offline-hours", 72)

    /** When an age ends, does the cast of the old story go back to the spirit world? */
    val retireCastOnAdvance = cfg.getBoolean("eras.retire-cast-on-advance", true)

    /** Essence for having played a part. A veteran gets a head start on the next age. */
    val retirementEssence = cfg.getInt("eras.retirement-essence", 25)

    /**
     * Ticks of dark, held silence between the old era's epilogue and the new one's
     * prologue. 60 = three seconds. Nothing can be claimed during a transition.
     */
    val interludeTicks = cfg.getLong("eras.interlude-ticks", 60)

    val hudEnabled = cfg.getBoolean("display.hud", true)
    val prefixNames = cfg.getBoolean("display.prefix-names", true)
    val broadcastClaims = cfg.getBoolean("display.broadcast-claims", true)
    val broadcastEras = cfg.getBoolean("display.broadcast-eras", true)

    fun essenceCost(tier: RoleTier): Int = cfg.getInt("claiming.essence-cost.${tier.name}", 0)
}
