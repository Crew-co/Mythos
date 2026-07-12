package net.crewco.mythos.command

/**
 * Marks a class as a command handler.
 *
 * Put this on a class, then annotate methods inside it with [Default],
 * [Subcommand], and/or [TabComplete]. Register an instance of the class with
 * `CommandManager.register(...)` and it becomes a live command.
 *
 * Example:
 * ```
 * @Command(name = "kit", aliases = ["kits"], permission = "myplugin.kit")
 * class KitCommand { ... }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Command(
    /** The primary command name, used as `/name`. */
    val name: String,
    /** Shown in `/help` and error messages. */
    val description: String = "",
    /** Usage hint, shown when a handler reports bad input. */
    val usage: String = "",
    /** Extra names that trigger the same command, e.g. `/kits`. */
    val aliases: Array<String> = [],
    /** Permission required to use the command at all. Empty = everyone. */
    val permission: String = "",
    /** If true, console/command blocks are rejected with a message. */
    val playerOnly: Boolean = false,
)

/**
 * Marks the method invoked when the command is run with no matching subcommand
 * (i.e. `/kit` on its own, or `/kit <unknown>`). The handler receives the full
 * argument list in `ctx.args`.
 *
 * Like [Subcommand], the default handler can require a permission, restrict to
 * players, and demand a minimum number of args — all enforced before it runs.
 *
 * The method must take a single [CommandContext] parameter.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Default(
    /** Permission for the default handler, checked in addition to the class permission. */
    val permission: String = "",
    /** If true, only players may trigger the default handler. */
    val playerOnly: Boolean = false,
    /** Minimum number of args before the handler runs. */
    val minArgs: Int = 0,
    /** Usage hint shown when too few args are given. */
    val usage: String = "",
)

/**
 * Marks a method as a subcommand handler, triggered when the first argument
 * matches [name] (or one of [aliases]). The remaining args are shifted so that
 * inside the method, `ctx.args[0]` is the first argument *after* the subcommand.
 *
 * The method must take a single [CommandContext] parameter.
 *
 * Example: for `/kit give Steve`, a method annotated `@Subcommand("give")`
 * receives a context whose `args` is `["Steve"]`.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Subcommand(
    val name: String,
    val aliases: Array<String> = [],
    /** Per-subcommand permission, checked in addition to the class permission. */
    val permission: String = "",
    /** If true, only players may run this subcommand. */
    val playerOnly: Boolean = false,
    /** Minimum number of args (after the subcommand word) before the handler runs. */
    val minArgs: Int = 0,
    /** Usage hint shown when args are missing. */
    val usage: String = "",
    val description: String = "",
)

/**
 * Marks a method that produces tab-completions.
 *
 * If [subcommand] is empty, the method completes the first argument (typically
 * the list of subcommand names — provided automatically if you don't supply
 * your own). If [subcommand] is set, the method completes arguments *after*
 * that subcommand word.
 *
 * The method must take a single [CommandContext] and return
 * `List<String>` / `Collection<String>`.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class TabComplete(
    val subcommand: String = "",
)
