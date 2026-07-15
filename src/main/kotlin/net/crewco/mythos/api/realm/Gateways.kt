package net.crewco.mythos.api.realm

import org.bukkit.Location

/**
 * **A way in that you can walk to.**
 *
 * `/mythos realm underworld` is an admin command, not a mythology. The dead do not type. Heracles
 * did not type. There has to be a *cave*, in a *place*, that a person can find and go down.
 *
 * A gateway is a point in a world that sends whoever stands in it somewhere else — subject to the
 * destination realm's own access rules, so the entrance to the Underworld can be sitting in a field
 * in plain sight and still refuse the living.
 */
data class Gateway(
    /** Unique. "taenarum", "olympus-ascent". */
    val id: String,

    /** Where the mouth of it is. */
    val at: Location,

    /** Where it goes. */
    val toRealm: String,

    /** How close you have to be. Two blocks is a doorway; ten is a region. */
    val radius: Double = 2.0,

    /** Shown to whoever steps in and is allowed through. */
    val arrival: String = "",

    /**
     * Shown — once, then quietly — to whoever steps in and is refused. The refusal is the point:
     * a mortal *can* find the cave. That's what makes it frightening.
     */
    val refusal: String = "<dark_gray><i>The way is open. It is not open to you.",

    /**
     * An EXTRA condition on the door itself, on top of the destination realm's own rules.
     *
     * This exists because of one specific problem: the way *out* of the Underworld goes to Gaia,
     * and Gaia is open to everyone — so without a rule on the door, the dead would simply walk
     * home. The ascent requires you to be alive, or to be following someone who isn't looking.
     */
    val requires: RealmAccess = RealmRules.OPEN,

    /**
     * What the mouth of it looks like. e.g. "SOUL_FIRE_FLAME", "END_ROD", "FALLING_WATER".
     *
     * The engine used to hard-code soul-fire, which is a decision about *the Underworld* — and
     * Olympus is not the Underworld. The engine draws what you tell it to draw.
     */
    val particle: String? = "SOUL_FIRE_FLAME",

    /** And what it sounds like. e.g. "minecraft:block.portal.ambient". */
    val sound: String? = null,
)
