package net.crewco.mythos.command

/**
 * Throw this from anywhere inside a command handler to abort execution and show
 * the sender a message. The [CommandManager] catches it and replies for you.
 *
 * Example:
 * ```
 * val target = ctx.player(0) ?: fail("Player not found.")
 * ```
 */
class CommandException(message: String) : RuntimeException(message)

/** Convenience: abort the current command with a message. Never returns. */
fun fail(message: String): Nothing = throw CommandException(message)
