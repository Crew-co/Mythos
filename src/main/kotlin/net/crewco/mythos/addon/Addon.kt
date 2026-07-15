package net.crewco.mythos.addon

/**
 * Implement this in your addon jar and name the class in `addon.yml`'s `main:`.
 *
 * Lifecycle: the host constructs your class with a no-arg constructor, injects
 * an [AddonContext] via [init], then calls [onEnable]. On shutdown (or reload)
 * it calls [onDisable].
 *
 * Threading (Folia): [onEnable]/[onDisable] run on the host's plugin thread at
 * startup/shutdown. Anything you schedule afterwards must follow Folia's rules —
 * use `context.schedulers` rather than the legacy Bukkit scheduler.
 */
interface Addon {

    /** Set by the loader before [onEnable]. Available in every other method. */
    var context: AddonContext

    /** Called once, after all addons are constructed. Register your stuff here. */
    fun onEnable()

    /** Called on shutdown/reload. Undo anything that outlives the addon. */
    fun onDisable() {}
}

/**
 * Base class that stores the injected [context] for you, so an addon only has
 * to override [onEnable]. Kotlin addons should usually extend this.
 */
abstract class AddonBase : Addon {
    override lateinit var context: AddonContext
}
