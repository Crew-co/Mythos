package net.crewco.mythos.commands

import net.crewco.mythos.MythosPlugin
import net.crewco.mythos.command.Command
import net.crewco.mythos.command.CommandContext
import net.crewco.mythos.command.Default
import net.crewco.mythos.command.Subcommand

/** `/addons` to list, `/addons reload` to hot-reload every addon jar. */
@Command(name = "addons", permission = "mythos.addons", description = "Manage addons.")
class AddonsCommand(private val plugin: MythosPlugin) {

    @Default
    fun list(ctx: CommandContext) {
        val addons = plugin.addons.loadedAddons()
        if (addons.isEmpty()) {
            ctx.info("No addons loaded. Drop jars in <white>${plugin.addons.addonsFolder.path}</white>.")
            return
        }
        ctx.info("Loaded addons (${addons.size}):")
        addons.forEach { ctx.reply("<gray>  <white>${it.name}</white> v${it.version} <dark_gray>- ${it.description}") }
    }

    @Subcommand("reload", permission = "mythos.addons.reload", description = "Reload all addons.")
    fun reload(ctx: CommandContext) {
        // Disabling/enabling addons touches shared host state, so serialize it on
        // the global region rather than whichever region the sender happens to be on.
        val sender = ctx.sender
        plugin.schedulers.global {
            plugin.addons.reload()
            plugin.schedulers.forSender(sender) {
                ctx.success("Reloaded ${plugin.addons.loadedAddons().size} addon(s).")
            }
        }
    }
}
