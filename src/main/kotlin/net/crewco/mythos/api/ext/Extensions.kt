package net.crewco.mythos.api.ext

/**
 * How one story reaches into another. `mythos.extensions`.
 *
 * A story addon opens a hole in itself and says what may be posted through it:
 *
 * ```kotlin
 * // In LaboursOfHeracles — the point owner.
 * interface Labour { val id: String; val name: String; fun begin(hero: Player) }
 *
 * mythos.extensions.consume<Labour>("heracles:labours") { labour ->
 *     labours += labour                       // arrives whether it was contributed
 *     mythos.eras.addObjective(ERA, Objective(labour.id, labour.name))
 * }
 * ```
 *
 * ```kotlin
 * // In some jar written a year later, by someone else.
 * mythos.extensions.contribute("heracles:labours", TheThirteenthLabour())
 * ```
 *
 * **Load order does not matter.** [consume] replays every contribution already made and
 * receives every one made afterwards, so the contributor can enable before *or* after
 * the point owner. That single property is what makes an ecosystem of addons possible
 * instead of a fragile chain of hard `depends:`.
 *
 * The contributor still needs the point owner's *type* (`Labour`) at compile time —
 * `compileOnly` its jar and declare `depends:`, and the host's classloader will resolve
 * it to the one true class at runtime. For contributions that carry no behaviour, use a
 * plain data class and skip the dependency entirely.
 *
 * Everything an addon contributes is dropped when that addon unloads.
 */
interface ExtensionService {

    /** Post something through someone else's hole. */
    fun contribute(point: String, contribution: Any)

    /**
     * Receive everything posted to [point] — past and future. The consumer runs once per
     * contribution, on the thread that contributed it (usually an addon's onEnable).
     */
    fun <T : Any> consume(point: String, type: Class<T>, consumer: (T) -> Unit)

    fun contributions(point: String): List<Any>

    /** Every point anyone has contributed to or consumed. Useful for `/mythos points`. */
    fun points(): List<String>
}

/** Reified sugar: `mythos.extensions.consume<Labour>("heracles:labours") { ... }` */
inline fun <reified T : Any> ExtensionService.consume(point: String, noinline consumer: (T) -> Unit) =
    consume(point, T::class.java, consumer)
