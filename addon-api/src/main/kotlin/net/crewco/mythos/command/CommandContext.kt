package net.crewco.mythos.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Everything a command handler needs, wrapped in one object with convenience
 * accessors so you rarely touch raw arrays or do manual casting.
 *
 * Argument helpers return null (or throw [CommandException] via the `require*`
 * variants) rather than crashing, so handlers stay short.
 */
class CommandContext(
    /** Who ran the command (a [Player], the console, or a command block). */
    val sender: CommandSender,
    /** The label actually typed, e.g. `kit` or an alias like `kits`. */
    val label: String,
    /** Arguments after the command word (and after the subcommand, if any). */
    val args: Array<String>,
) {
    /** The sender as a [Player], or null if it was console / a command block. */
    val player: Player? get() = sender as? Player

    /** True if there are no arguments. */
    val isEmpty: Boolean get() = args.isEmpty()

    val size: Int get() = args.size

    // ---- raw argument access -------------------------------------------------

    /** The arg at [index], or null if out of range. */
    fun arg(index: Int): String? = args.getOrNull(index)

    /** The arg at [index], or [default] if out of range. */
    fun argOr(index: Int, default: String): String = args.getOrNull(index) ?: default

    /** Join args from [from] to the end, e.g. for free-text messages. */
    fun joinFrom(from: Int, separator: String = " "): String =
        if (from >= args.size) "" else args.drop(from).joinToString(separator)

    // ---- typed parsing (nullable) -------------------------------------------

    fun intArg(index: Int): Int? = args.getOrNull(index)?.toIntOrNull()
    fun doubleArg(index: Int): Double? = args.getOrNull(index)?.toDoubleOrNull()
    fun boolArg(index: Int): Boolean? = when (args.getOrNull(index)?.lowercase()) {
        "true", "yes", "on", "1" -> true
        "false", "no", "off", "0" -> false
        else -> null
    }

    /** An online player matching arg [index] by name, or null. */
    fun playerArg(index: Int): Player? = args.getOrNull(index)?.let { Bukkit.getPlayerExact(it) }

    // ---- typed parsing (throwing) -------------------------------------------
    // These abort the command with a friendly message when input is bad.

    fun requireInt(index: Int, what: String = "a number"): Int =
        intArg(index) ?: fail("Expected $what for argument ${index + 1}.")

    fun requireDouble(index: Int, what: String = "a number"): Double =
        doubleArg(index) ?: fail("Expected $what for argument ${index + 1}.")

    fun requirePlayer(index: Int): Player =
        playerArg(index) ?: fail("Player '${argOr(index, "?")}' is not online.")

    /** Require that the *sender* is a player (not console). */
    fun requireSenderPlayer(): Player =
        player ?: fail("This can only be run by a player.")

    // ---- replying ------------------------------------------------------------
    // Strings are parsed as MiniMessage, so you can write "<green>Done!</green>".

    fun reply(message: String) = sender.sendMessage(mm(message))
    fun reply(component: Component) = sender.sendMessage(component)
    fun error(message: String) = sender.sendMessage(mm("<red>$message</red>"))
    fun success(message: String) = sender.sendMessage(mm("<green>$message</green>"))
    fun info(message: String) = sender.sendMessage(mm("<gray>$message</gray>"))

    /** True if the sender has [permission] (or it's blank). */
    fun hasPermission(permission: String): Boolean =
        permission.isBlank() || sender.hasPermission(permission)

    companion object {
        private val MM = MiniMessage.miniMessage()
        fun mm(text: String): Component = MM.deserialize(text)
    }
}
