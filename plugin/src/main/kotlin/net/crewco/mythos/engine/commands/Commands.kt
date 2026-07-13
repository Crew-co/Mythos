package net.crewco.mythos.engine.commands

import net.crewco.mythos.api.role.ClaimResult
import net.crewco.mythos.command.Command
import net.crewco.mythos.command.CommandContext
import net.crewco.mythos.command.Default
import net.crewco.mythos.command.Subcommand
import net.crewco.mythos.command.TabComplete
import net.crewco.mythos.engine.AltarMenu
import net.crewco.mythos.engine.MythosEngine
import org.bukkit.Bukkit

/**
 * The player-facing surface of core. Story addons add their own commands; these
 * are the ones that exist in every age.
 */

@Command(name = "claim", description = "Take up a mantle that has no wearer.", playerOnly = true)
class ClaimCommand(private val core: MythosEngine) {

    @Default
    fun claim(ctx: CommandContext) {
        val player = ctx.requireSenderPlayer()

        // No arguments: open the Altar of Fate. It shows what you can take, and — greyed
        // out, with the reason — what you can't.
        val roleId = ctx.arg(0) ?: run {
            core.menus.open(player, AltarMenu(core))
            return
        }
        if (roleId.equals("list", ignoreCase = true)) {
            listOpen(ctx)
            return
        }

        when (val result = core.roles.claim(player, roleId)) {
            is ClaimResult.Allow -> Unit // the service handles titles, broadcasts, everything
            is ClaimResult.Deny -> ctx.error(result.reason)
        }
    }

    /**
     * What YOU can take, then — dimmed, and only if you ask — what you can't, and why.
     *
     * The old version listed every vacant seat, including the ones no player may ever
     * claim (a Titan is *born*, not claimed), and then denied you when you tried. Never
     * advertise a mantle you're going to refuse.
     */
    private fun listOpen(ctx: CommandContext) {
        val player = ctx.requireSenderPlayer()
        val yours = core.roles.claimableBy(player)
        val vacant = core.roles.openRoles()

        if (yours.isEmpty() && vacant.isEmpty()) {
            ctx.info("Every mantle is worn. Wait — they never last.")
            return
        }

        if (yours.isEmpty()) {
            ctx.info("Nothing is open to you right now.")
        } else {
            ctx.info("Yours for the taking:")
            yours.forEach { role ->
                val offered = core.spirits.offerHolderOf(role.id)?.let { Bukkit.getPlayer(it)?.name }
                val suffix = if (offered != null && offered != player.name) " <dark_gray><i>(offered to $offered)" else ""
                ctx.reply("<dark_gray>  · ${role.color}${role.displayName} <dark_gray>[${role.tier.displayName}]$suffix")
            }
            ctx.reply("<gray>  /claim <white><role></white>")
        }

        // The locked ones, with the actual reason. This is the interesting part of the
        // screen: "Titans are not claimed. They are born." tells you how the world works.
        val locked = vacant.filter { it !in yours }
            .mapNotNull { role ->
                (core.roles.evaluate(player, role.id) as? ClaimResult.Deny)?.let { role to it.reason }
            }
        if (locked.isNotEmpty()) {
            ctx.reply("")
            ctx.reply("<dark_gray>Vacant, but closed to you:")
            locked.take(8).forEach { (role, reason) ->
                ctx.reply("<dark_gray>  · <gray>${role.displayName} <dark_gray>— <i>$reason")
            }
        }
    }

    @TabComplete
    fun completeClaim(ctx: CommandContext) =
        ctx.player?.let { p -> core.roles.claimableBy(p).map { it.id } }.orEmpty() + "list"

}

@Command(name = "roles", description = "Every role in the myth, and who wears it.")
class RolesCommand(private val core: MythosEngine) {

    @Default
    fun list(ctx: CommandContext) {
        val eraFilter = ctx.arg(0)
        val roles = if (eraFilter != null) core.roles.definitionsInEra(eraFilter) else core.roles.definitions()
        if (roles.isEmpty()) {
            ctx.info("No roles registered. Install a story addon.")
            return
        }
        ctx.info("Roles${if (eraFilter != null) " of the $eraFilter" else ""}:")
        roles.groupBy { it.tier }.forEach { (tier, group) ->
            ctx.reply("<dark_gray>— <gray>${tier.displayName}")
            group.forEach { role ->
                val holders = core.roles.holders(role.id).mapNotNull { core.profiles.profile(it).name }
                val who = when {
                    core.roles.isSealed(role.id) -> "<dark_gray><i>sealed"
                    holders.isEmpty() -> "<dark_gray><i>vacant"
                    else -> "<white>${holders.joinToString()}"
                }
                ctx.reply("<dark_gray>   ${role.color}${role.displayName} <dark_gray>— $who")
            }
        }
    }

    @TabComplete
    fun complete(ctx: CommandContext) = core.eras.eras().map { it.id }
}

@Command(name = "role", description = "Your mantle: what it grants, and who inherits it.", playerOnly = true)
class RoleCommand(private val core: MythosEngine) {

    @Default
    fun info(ctx: CommandContext) {
        val player = ctx.requireSenderPlayer()
        val role = core.roles.roleOf(player.uniqueId) ?: run {
            ctx.info("You are a spirit. <white>/spirit")
            return
        }
        ctx.reply("${role.color}<b>${role.displayName}</b> <dark_gray>— ${role.tier.displayName}")
        role.lore.forEach { ctx.reply("<dark_gray>| <gray><i>$it") }
        if (role.domains.isNotEmpty()) ctx.reply("<gray>Domains: <white>${role.domains.joinToString()}")
        val powers = core.powers.powersOf(player.uniqueId)
        if (powers.isNotEmpty()) ctx.reply("<gray>Powers: <white>${powers.joinToString { it.displayName }}")
        core.roles.heirOf(player.uniqueId)?.let {
            ctx.reply("<gray>Heir: <white>${core.profiles.profile(it).name}")
        }
    }

    @Subcommand("heir", minArgs = 1, usage = "/role heir <player>", description = "Name who inherits your mantle.")
    fun heir(ctx: CommandContext) {
        val player = ctx.requireSenderPlayer()
        val role = core.roles.roleOf(player.uniqueId) ?: return ctx.error("You have no mantle to pass on.")
        val target = ctx.requirePlayer(0)
        core.roles.setHeir(player.uniqueId, target.uniqueId)
        ctx.success("${target.name} will inherit ${role.displayName}. <gray>(If the role allows it.)")
    }

    @Subcommand("abdicate", description = "Give up your mantle and return to the spirit world.")
    fun abdicate(ctx: CommandContext) {
        val player = ctx.requireSenderPlayer()
        val role = core.roles.roleOf(player.uniqueId) ?: return ctx.error("You are already nobody.")
        if (ctx.argOr(0, "") != "confirm") {
            ctx.error("This is not undone. <white>/role abdicate confirm <red>to lay down ${role.displayName}.")
            return
        }
        core.schedulers.global { core.roles.release(player.uniqueId, "abdicated the name of ${role.displayName}") }
    }

    @TabComplete(subcommand = "heir")
    fun completeHeir(ctx: CommandContext) = Bukkit.getOnlinePlayers().map { it.name }
}

@Command(name = "spirit", aliases = ["ghost"], description = "The state of the waiting dead.", playerOnly = true)
class SpiritCommand(private val core: MythosEngine) {

    @Default
    fun status(ctx: CommandContext) {
        val player = ctx.requireSenderPlayer()
        val uuid = player.uniqueId
        if (!core.spirits.isSpirit(uuid)) {
            ctx.info("You have a body and a name. <white>/role")
            return
        }
        val position = core.spirits.queuePosition(uuid)
        val interest = core.spirits.interestOf(uuid)
        ctx.reply("<dark_gray>— <gray>You are a <white>spirit<gray>, one of <white>${core.spirits.spirits().size}<gray>.")
        ctx.reply("<gray>Essence: <white>${core.spirits.essence(uuid)}")
        ctx.reply("<gray>In line: <white>${if (position < 0) "not queued" else "#${position + 1} of ${core.spirits.queue().size}"}")
        ctx.reply("<gray>Waiting for: <white>${interest ?: "any mantle at all"}")
        core.spirits.pendingOffer(uuid)?.let {
            val role = core.roles.definition(it)
            ctx.reply("<gold>You have been offered ${role?.displayName ?: it}! <white>/claim $it")
        }
        val yours = core.roles.claimableBy(player)
        if (yours.isNotEmpty()) {
            ctx.reply("<gray>Open to you: <white>${yours.joinToString { it.displayName }} <dark_gray>(/claim)")
        } else {
            ctx.reply("<dark_gray><i>Nothing is open to you yet. Wait. Something always falls vacant.")
        }
    }

    @Subcommand("queue", minArgs = 0, usage = "/spirit queue [role|any]", description = "Wait for a mantle.")
    fun queue(ctx: CommandContext) {
        val player = ctx.requireSenderPlayer()
        val target = ctx.arg(0)?.lowercase()
        if (target != null && target != "any" && core.roles.definition(target) == null) {
            return ctx.error("No such role.")
        }
        core.spirits.enqueue(player.uniqueId, if (target == "any") null else target)
        ctx.success("You wait for ${if (target == null || target == "any") "whatever falls vacant" else target}.")
        core.saveState()
    }

    @Subcommand("decline", description = "Pass an offered mantle to the next spirit in line.")
    fun decline(ctx: CommandContext) {
        val player = ctx.requireSenderPlayer()
        val offer = core.spirits.pendingOffer(player.uniqueId) ?: return ctx.error("Nothing has been offered to you.")
        core.spirits.decline(player.uniqueId)
        ctx.info("You let $offer pass you by. <dark_gray>(You go to the back of the queue.)")
    }

    @Subcommand("leave", description = "Stop waiting for anything.")
    fun leave(ctx: CommandContext) {
        val player = ctx.requireSenderPlayer()
        core.spirits.dequeue(player.uniqueId)
        ctx.info("You stop waiting. Essence still gathers.")
        core.saveState()
    }

    @Subcommand("list", description = "Who else is waiting.")
    fun list(ctx: CommandContext) {
        val queue = core.spirits.queue()
        if (queue.isEmpty()) return ctx.info("Nobody waits.")
        ctx.info("The queue (${queue.size}):")
        queue.take(15).forEachIndexed { index, uuid ->
            val profile = core.profiles.profile(uuid)
            val want = core.spirits.interestOf(uuid) ?: "any"
            ctx.reply("<dark_gray>  ${index + 1}. <white>${profile.name} <dark_gray>— wants <gray>$want <dark_gray>· ${profile.essence} essence")
        }
    }

    @TabComplete(subcommand = "queue")
    fun completeQueue(ctx: CommandContext) =
        core.roles.definitions().map { it.id } + "any" // you may queue for a seat that isn't free yet
}

@Command(name = "era", aliases = ["age"], description = "Where the story stands.")
class EraCommand(private val core: MythosEngine) {

    @Default
    fun status(ctx: CommandContext) {
        val era = core.eras.current() ?: return ctx.info("The world has not begun. No era is registered.")
        ctx.reply("<gold><b>${era.displayName}</b> <dark_gray>— ${era.subtitle}")
        era.lore.forEach { ctx.reply("<dark_gray>| <gray><i>$it") }
        ctx.reply("")
        core.eras.objectives(era.id).filter { !it.hidden || core.eras.isComplete(era.id, it.id) }.forEach { objective ->
            val done = core.eras.isComplete(era.id, objective.id)
            val mark = if (done) "<green>x" else "<dark_gray>o"
            val text = if (done) "<dark_gray><st>${objective.description}</st>" else "<gray>${objective.description}"
            ctx.reply("  $mark $text")
        }
        val remaining = core.eras.objectives(era.id).count { !it.optional && !core.eras.isComplete(era.id, it.id) }
        ctx.reply("")
        ctx.reply(
            if (remaining == 0) "<gold>The age is complete."
            else "<dark_gray><i>$remaining beat(s) remain before ${core.eras.nextOf(era.id) ?: "the end of the story"}.",
        )
    }

    @Subcommand("all", description = "The whole shape of the story.")
    fun all(ctx: CommandContext) {
        val eras = core.eras.chain() // as it is NOW — including anything spliced in
        if (eras.isEmpty()) return ctx.info("No eras registered.")
        ctx.info("The ages:")
        eras.forEach { era ->
            val marker = when {
                era.id == core.eras.currentId() -> "<gold>» "
                core.eras.isPast(era.id) -> "<dark_gray>  "
                else -> "<dark_gray>  "
            }
            val name = when {
                era.id == core.eras.currentId() -> "<gold>${era.displayName}"
                core.eras.isPast(era.id) -> "<dark_gray><st>${era.displayName}</st>"
                else -> "<gray>${era.displayName}"
            }
            ctx.reply("$marker$name")
        }
    }
}

@Command(name = "power", aliases = ["powers"], description = "Wield what your mantle grants.", playerOnly = true)
class PowerCommand(private val core: MythosEngine) {

    @Default
    fun use(ctx: CommandContext) {
        val player = ctx.requireSenderPlayer()
        val id = ctx.arg(0) ?: run {
            val powers = core.powers.powersOf(player.uniqueId)
            if (powers.isEmpty()) {
                ctx.info("You have no powers. Spirits rarely do.")
                return
            }
            ctx.info("Your powers:")
            powers.forEach { power ->
                val cooldown = core.powers.cooldown(player.uniqueId, power.id)
                val state = if (cooldown > 0) "<red>${cooldown}s" else "<green>ready"
                ctx.reply("<dark_gray>  · <white>/power ${power.id} <dark_gray>[$state]")
                ctx.reply("<dark_gray>      <gray><i>${power.description}")
            }
            return
        }
        core.powers.use(player, id, ctx.args.drop(1))
    }

    @TabComplete
    fun complete(ctx: CommandContext) =
        ctx.player?.let { core.powers.powersOf(it.uniqueId).map { power -> power.id } }.orEmpty()
}

@Command(name = "mythos", permission = "mythos.admin", description = "Bend the story to your will.")
class MythosAdminCommand(private val core: MythosEngine) {

    @Subcommand("assign", minArgs = 2, usage = "/mythos assign <player> <role>", description = "Force a role onto a player.")
    fun assign(ctx: CommandContext) {
        val target = ctx.requirePlayer(0)
        val roleId = ctx.args[1].lowercase()
        val role = core.roles.definition(roleId) ?: return ctx.error("No such role.")
        core.schedulers.global {
            core.roles.assign(target.uniqueId, role.id, "assigned by ${ctx.sender.name}")
        }
        ctx.success("${target.name} is now ${role.displayName}.")
    }

    @Subcommand("release", minArgs = 1, usage = "/mythos release <player>", description = "Strip a player's role.")
    fun release(ctx: CommandContext) {
        val target = ctx.requirePlayer(0)
        core.schedulers.global { core.roles.release(target.uniqueId, "stripped by ${ctx.sender.name}") }
        ctx.success("Done.")
    }

    @Subcommand("seal", minArgs = 1, usage = "/mythos seal <role>")
    fun seal(ctx: CommandContext) {
        val roleId = ctx.args[0].lowercase()
        core.schedulers.global { core.roles.seal(roleId, "sealed by ${ctx.sender.name}") }
        ctx.success("$roleId is sealed.")
    }

    @Subcommand("open", minArgs = 1, usage = "/mythos open <role>")
    fun open(ctx: CommandContext) {
        val roleId = ctx.args[0].lowercase()
        core.schedulers.global { core.roles.open(roleId, "opened by ${ctx.sender.name}") }
        ctx.success("$roleId is open.")
    }

    @Subcommand("advance", minArgs = 1, usage = "/mythos advance <era>", description = "Force the world into an age.")
    fun advance(ctx: CommandContext) {
        val eraId = ctx.args[0].lowercase()
        if (!core.eras.advance(eraId, "willed by ${ctx.sender.name}")) ctx.error("No such era (is its addon installed?)")
    }

    @Subcommand("complete", minArgs = 1, usage = "/mythos complete <objective>", description = "Strike a beat of the current era.")
    fun complete(ctx: CommandContext) {
        val era = core.eras.current() ?: return ctx.error("No era.")
        core.eras.complete(era.id, ctx.args[0], "willed by ${ctx.sender.name}")
        ctx.success("Struck.")
    }

    @Subcommand("essence", minArgs = 2, usage = "/mythos essence <player> <amount>")
    fun essence(ctx: CommandContext) {
        val target = ctx.requirePlayer(0)
        val amount = ctx.requireInt(1)
        core.spirits.grantEssence(target.uniqueId, amount, "a gift from ${ctx.sender.name}")
        ctx.success("Granted.")
    }

    @Subcommand("save", description = "Flush everything to disk.")
    fun save(ctx: CommandContext) {
        core.schedulers.async {
            core.profiles.saveAll()
            core.saveStateNow()
        }
        ctx.success("Written.")
    }

    @TabComplete(subcommand = "assign")
    fun completeAssign(ctx: CommandContext) =
        if (ctx.size <= 1) Bukkit.getOnlinePlayers().map { it.name } else core.roles.definitions().map { it.id }

    @TabComplete(subcommand = "advance")
    fun completeAdvance(ctx: CommandContext) = core.eras.eras().map { it.id }

    @TabComplete(subcommand = "seal")
    fun completeSeal(ctx: CommandContext) = core.roles.definitions().map { it.id }
}

/**
 * `/chronicle` — the history of the world.
 *
 * The engine writes to this on its own; story addons add the things only they know are
 * important. A player who joins during the Trojan War can read what the Titans did to
 * each other four ages ago, and who did it, by name.
 */
@Command(name = "chronicle", aliases = ["history"], description = "What has happened, in order, forever.")
class ChronicleCommand(private val core: MythosEngine) {

    @Default
    fun recent(ctx: CommandContext) {
        val entries = core.chronicle.entries(15)
        if (entries.isEmpty()) {
            ctx.info("Nothing has happened yet. The world is very new.")
            return
        }
        ctx.reply("<gold><b>The Chronicle</b> <dark_gray>— ${core.chronicle.size()} entries")
        entries.reversed().forEach { entry ->
            ctx.reply("<dark_gray>[${entry.era}] <reset>${entry.text}")
        }
        ctx.reply("<dark_gray><i>/chronicle <era> for one age · /era all for the shape of the story")
    }

    @Subcommand("era", minArgs = 1, usage = "/chronicle era <era>", description = "One age, from beginning to end.")
    fun ofEra(ctx: CommandContext) {
        val eraId = ctx.args[0].lowercase()
        val era = core.eras.era(eraId) ?: return ctx.error("No such age.")
        val entries = core.chronicle.entriesOfEra(eraId, 40)
        if (entries.isEmpty()) return ctx.info("Nothing is recorded of the ${era.displayName}.")

        ctx.reply("<gold><b>${era.displayName}</b>")
        entries.reversed().forEach { ctx.reply("<dark_gray>· <reset>${it.text}") }
    }

    @TabComplete(subcommand = "era")
    fun complete(ctx: CommandContext) = core.eras.chain().map { it.id }
}
