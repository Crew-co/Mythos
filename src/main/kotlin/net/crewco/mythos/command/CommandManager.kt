package net.crewco.mythos.command

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.logging.Level
import org.bukkit.command.Command as BukkitCommand

/**
 * Registers annotated command handlers with the server.
 *
 * Usage from your plugin's onEnable:
 * ```
 * val commands = CommandManager(this)
 * commands.register(ExampleCommand(this))
 * ```
 *
 * Under the hood this reads the [Command]/[Subcommand]/[Default]/[TabComplete]
 * annotations via reflection, builds a Bukkit [BukkitCommand], and inserts it
 * into the server's command map — no `commands:` block in plugin.yml required.
 */
class CommandManager(private val plugin: JavaPlugin) {

    private val fallbackPrefix = plugin.name.lowercase()

    /** Register a single annotated handler instance. */
    fun register(handler: Any) {
        val meta = handler.javaClass.getAnnotation(Command::class.java)
            ?: error("${handler.javaClass.name} is missing the @Command annotation")

        val index = index(handler)
        val command = Dispatcher(plugin, handler, meta, index)
        plugin.server.commandMap.register(fallbackPrefix, command)
    }

    /** Register several handlers at once. */
    fun registerAll(vararg handlers: Any) = handlers.forEach(::register)

    // ---- reflection: build a lookup table for one handler class --------------

    private fun index(handler: Any): HandlerIndex {
        var default: DefaultEntry? = null
        val subs = HashMap<String, SubEntry>()
        val subNames = ArrayList<String>()
        var defaultTab: Method? = null
        val subTab = HashMap<String, Method>()

        for (method in handler.javaClass.declaredMethods) {
            method.getAnnotation(Default::class.java)?.let { d ->
                requireCtxParam(method)
                method.isAccessible = true // done once here (single-threaded) — never per-call
                default = DefaultEntry(method, d)
            }
            method.getAnnotation(Subcommand::class.java)?.let { sc ->
                requireCtxParam(method)
                method.isAccessible = true
                val entry = SubEntry(method, sc)
                subNames += sc.name
                (listOf(sc.name) + sc.aliases).forEach { subs[it.lowercase()] = entry }
            }
            method.getAnnotation(TabComplete::class.java)?.let { tc ->
                method.isAccessible = true
                if (tc.subcommand.isBlank()) defaultTab = method
                else subTab[tc.subcommand.lowercase()] = method
            }
        }
        // Copy into read-only structures. After this the index is never mutated,
        // so it can be shared across region threads without synchronization.
        return HandlerIndex(default, subs.toMap(), subNames.toList(), defaultTab, subTab.toMap())
    }

    private fun requireCtxParam(method: Method) {
        require(method.parameterCount == 1 && method.parameterTypes[0] == CommandContext::class.java) {
            "${method.declaringClass.simpleName}#${method.name} must take exactly one CommandContext parameter"
        }
    }

    private data class SubEntry(val method: Method, val meta: Subcommand)

    private data class DefaultEntry(val method: Method, val meta: Default)

    private class HandlerIndex(
        val default: DefaultEntry?,
        val subs: Map<String, SubEntry>,
        val subNames: List<String>,
        val defaultTab: Method?,
        val subTab: Map<String, Method>,
    )

    // ---- the Bukkit command that actually runs -------------------------------

    private inner class Dispatcher(
        private val plugin: JavaPlugin,
        private val handler: Any,
        private val meta: Command,
        private val idx: HandlerIndex,
    ) : BukkitCommand(
        meta.name,
        meta.description,
        meta.usage.ifBlank { "/${meta.name}" },
        meta.aliases.toList(),
    ) {
        init {
            if (meta.permission.isNotBlank()) permission = meta.permission
        }

        override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
            try {
                if (meta.playerOnly && sender !is Player) {
                    CommandContext(sender, label, emptyArray()).error("This command can only be used by players.")
                    return true
                }
                if (!sender.hasPermissionOrBlank(meta.permission)) {
                    noPermission(sender, label)
                    return true
                }

                // Try to route to a subcommand.
                if (args.isNotEmpty()) {
                    val entry = idx.subs[args[0].lowercase()]
                    if (entry != null) {
                        runSubcommand(sender, label, entry, args)
                        return true
                    }
                }

                // Otherwise fall back to the @Default handler...
                val def = idx.default
                if (def != null) {
                    runDefault(sender, label, def, args)
                } else {
                    // ...or, if there is no default, show generated help.
                    sendHelp(sender, label)
                }
            } catch (e: CommandException) {
                CommandContext(sender, label, emptyArray()).error(e.message ?: "Command failed.")
            } catch (e: Exception) {
                CommandContext(sender, label, emptyArray()).error("An internal error occurred.")
                plugin.logger.log(Level.SEVERE, "Error while executing /$label", e)
            }
            return true
        }

        private fun runDefault(sender: CommandSender, label: String, entry: DefaultEntry, args: Array<out String>) {
            val def = entry.meta
            if (def.playerOnly && sender !is Player) {
                CommandContext(sender, label, emptyArray()).error("This command can only be used by players.")
                return
            }
            if (!sender.hasPermissionOrBlank(def.permission)) {
                noPermission(sender, label)
                return
            }
            // The default handler gets the full arg list (no subcommand word to strip).
            val defArgs = copy(args)
            if (defArgs.size < def.minArgs) {
                val usage = def.usage.ifBlank { "/$label" }
                CommandContext(sender, label, defArgs).error("Usage: $usage")
                return
            }
            call(entry.method, CommandContext(sender, label, defArgs))
        }

        private fun runSubcommand(sender: CommandSender, label: String, entry: SubEntry, args: Array<out String>) {
            val sc = entry.meta
            if (sc.playerOnly && sender !is Player) {
                CommandContext(sender, label, emptyArray()).error("'/$label ${sc.name}' can only be used by players.")
                return
            }
            if (!sender.hasPermissionOrBlank(sc.permission)) {
                noPermission(sender, label)
                return
            }
            val subArgs = copy(args, from = 1)
            if (subArgs.size < sc.minArgs) {
                val usage = sc.usage.ifBlank { "/$label ${sc.name}" }
                CommandContext(sender, label, subArgs).error("Usage: $usage")
                return
            }
            call(entry.method, CommandContext(sender, label, subArgs))
        }

        override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): List<String> {
            if (!sender.hasPermissionOrBlank(meta.permission)) return emptyList()

            // Completing the first token → subcommand names + any custom @TabComplete("").
            if (args.size <= 1) {
                val partial = (args.firstOrNull() ?: "").lowercase()
                val suggestions = LinkedHashSet<String>()
                idx.subNames.forEach { name ->
                    val entry = idx.subs[name.lowercase()]
                    if (entry != null && sender.hasPermissionOrBlank(entry.meta.permission)) suggestions += name
                }
                idx.defaultTab?.let { suggestions += suggest(it, CommandContext(sender, alias, copy(args))) }
                return suggestions.filter { it.lowercase().startsWith(partial) }.sorted()
            }

            // Completing later tokens → delegate to that subcommand's @TabComplete.
            val tab = idx.subTab[args[0].lowercase()] ?: return emptyList()
            val subArgs = copy(args, from = 1)
            val partial = subArgs.lastOrNull()?.lowercase() ?: ""
            return suggest(tab, CommandContext(sender, alias, subArgs))
                .filter { it.lowercase().startsWith(partial) }
                .sorted()
        }

        private fun sendHelp(sender: CommandSender, label: String) {
            val ctx = CommandContext(sender, label, emptyArray())
            if (idx.subNames.isEmpty()) {
                ctx.error("Unknown command.")
                return
            }
            ctx.info("Subcommands for /$label:")
            idx.subNames.sorted().forEach { name ->
                val sc = idx.subs[name.lowercase()]?.meta
                val desc = sc?.description?.ifBlank { null }
                ctx.reply("<gray>  /</gray><white>$label $name</white>" + if (desc != null) " <dark_gray>- $desc</dark_gray>" else "")
            }
        }

        private fun noPermission(sender: CommandSender, label: String) =
            CommandContext(sender, label, emptyArray()).error("You don't have permission to do that.")

        private fun call(method: Method, ctx: CommandContext): Any? = try {
            // accessibility is set once at index time, so invoke is a pure read here
            method.invoke(handler, ctx)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e // unwrap so CommandException surfaces cleanly
        }

        @Suppress("UNCHECKED_CAST")
        private fun suggest(method: Method, ctx: CommandContext): List<String> {
            val result = call(method, ctx) ?: return emptyList()
            return (result as? Collection<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        }
    }

    private fun CommandSender.hasPermissionOrBlank(perm: String) =
        perm.isBlank() || hasPermission(perm)

    /** Copy a possibly-variant Bukkit args array into a plain Array<String>. */
    private fun copy(args: Array<out String>, from: Int = 0): Array<String> =
        if (from >= args.size) emptyArray() else Array(args.size - from) { args[it + from] }
}
