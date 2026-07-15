package net.crewco.mythos.engine.commands

import net.crewco.mythos.api.role.ClaimResult
import net.crewco.mythos.command.Command
import net.crewco.mythos.command.CommandContext
import net.crewco.mythos.command.Default
import net.crewco.mythos.command.Subcommand
import net.crewco.mythos.command.TabComplete
import net.crewco.mythos.engine.AltarMenu
import net.crewco.mythos.engine.MythosEngine
import net.crewco.mythos.engine.StoryState
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
                val holders = core.roles.holders(role.id).map { core.profiles.profile(it).name }
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

@Command(name = "story", aliases = ["recap"], description = "Where the story stands, and how it got here.")
class StoryCommand(private val core: MythosEngine) {

    @Default
    fun recap(ctx: CommandContext) {
        val era = core.eras.current()
        if (era == null || core.storyState == StoryState.IDLE) {
            ctx.info("The story has not begun.")
            return
        }

        ctx.reply("<gold><b>${era.displayName}</b> <gray>— <i>${era.subtitle}</i>")
        if (core.storyState == StoryState.PAUSED) ctx.reply("<yellow>The story is paused.")

        core.eras.objectives(era.id)
            .filter { !it.hidden || core.eras.isComplete(era.id, it.id) }
            .forEach { o ->
                val done = core.eras.isComplete(era.id, o.id)
                ctx.reply(if (done) "  <green>✔ <gray>${o.description}" else "  <dark_gray>▢ <gray>${o.description}")
            }

        val recent = core.chronicle.entries(3)
        if (recent.isNotEmpty()) {
            ctx.reply("<dark_gray><i>Lately:")
            recent.forEach { ctx.reply("  <dark_gray>• ${it.text}") }
        }
    }
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

    @Subcommand("forward", aliases = ["next"], description = "Move the story on: strike the next required beat, or turn the age.")
    fun forward(ctx: CommandContext) {
        if (ctx.arg(0)?.lowercase() in setOf("preview", "peek", "?")) {
            return ctx.info(core.director.preview() ?: "No era is running.")
        }
        core.schedulers.global {
            val what = core.director.forward("forwarded by ${ctx.sender.name}")
            ctx.reply(if (what != null) "<green>» $what" else "<gray>Nothing to forward — no era is running.")
        }
    }

    @Subcommand("story", minArgs = 1, usage = "/mythos story <start|stop|pause|resume|status>", description = "Run the story: begin it, hold it, or halt it.")
    fun story(ctx: CommandContext) {
        val result = when (ctx.args[0].lowercase()) {
            "start" -> core.startStory(ctx.sender.name)
            "stop" -> core.stopStory(ctx.sender.name)
            "pause" -> core.pauseStory(ctx.sender.name)
            "resume" -> core.resumeStory(ctx.sender.name)
            "status", "state" -> core.storySummary()
            else -> return ctx.error("start | stop | pause | resume | status")
        }
        ctx.info(result)
    }

    @Subcommand("essence", minArgs = 2, usage = "/mythos essence <player> <amount>")
    fun essence(ctx: CommandContext) {
        val target = ctx.requirePlayer(0)
        val amount = ctx.requireInt(1)
        core.spirits.grantEssence(target.uniqueId, amount, "a gift from ${ctx.sender.name}")
        ctx.success("Granted.")
    }

    // ---- the tools you need when you're the only one on the server ----------

    @Subcommand("dev", description = "Solo mode: gates, costs and cooldowns off; crowd-sized numbers become 1.")
    fun dev(ctx: CommandContext) {
        val turningOn = !core.dev.enabled
        core.dev.set(turningOn, ctx.sender.name)
        if (turningOn) {
            ctx.reply("<gray>Claim anything. No essence, no queue, no cooldowns.")
            ctx.reply("<gray>Every story's crowd-sized number is now <white>1<gray>: one kill ends the war,")
            ctx.reply("<gray>one throne is twelve thrones, one child is six children.")
            ctx.reply("<dark_gray><i>Story addons opt into this by asking mythos.dev.threshold(n). Ones that don't, don't.")
        }
    }

    @Subcommand("reset", minArgs = 1, usage = "/mythos reset <world|story|player|powers|chronicle> [args] confirm")
    fun reset(ctx: CommandContext) {
        val what = ctx.args[0].lowercase()
        val confirmed = ctx.args.lastOrNull().equals("confirm", ignoreCase = true)

        when (what) {
            "world" -> {
                if (!confirmed) {
                    ctx.error("This deletes <white>everything<red>: every role, every era, every profile,")
                    ctx.error("every point of essence, every epithet, and the whole Chronicle.")
                    ctx.error("<white>/mythos reset world confirm")
                    return
                }
                core.resetWorld(ctx.sender.name)
                ctx.success("The world is unmade. It will begin again in a moment.")
            }

            "story" -> {
                if (!confirmed) {
                    ctx.error("Rewinds the ages and strips every mantle. Players <white>keep<red> their essence,")
                    ctx.error("their epithets and their past lives — so this is the one you want for testing.")
                    ctx.error("<white>/mythos reset story confirm")
                    return
                }
                core.resetStory(ctx.sender.name)
                ctx.success("None of it happened. Back to the first age.")
            }

            "player" -> {
                if (ctx.size < 2) return ctx.error("/mythos reset player <name> confirm")
                val target = ctx.requirePlayer(1)
                if (!confirmed) {
                    ctx.error("Wipes ${target.name} back to a nameless spirit. <white>/mythos reset player ${target.name} confirm")
                    return
                }
                core.resetPlayer(target.uniqueId, ctx.sender.name)
                ctx.success("${target.name} has been unwritten.")
            }

            "powers" -> {
                val target = if (ctx.size >= 2) ctx.requirePlayer(1) else null
                core.powers.clearCooldowns(target?.uniqueId)
                ctx.success(if (target == null) "Every cooldown on the server is gone." else "${target.name}'s cooldowns are gone.")
            }

            "chronicle" -> {
                if (!confirmed) return ctx.error("Erases the history of the world. <white>/mythos reset chronicle confirm")
                core.chronicle.clear()
                ctx.success("Nobody remembers anything.")
            }

            else -> ctx.error("world · story · player <name> · powers [player] · chronicle")
        }
    }

    @Subcommand("realms", description = "The cosmos: every world an addon declared.")
    fun realms(ctx: CommandContext) {
        val realms = core.realms.realms()
        if (realms.isEmpty()) return ctx.info("There is no cosmos. Install a story addon.")
        ctx.info("The cosmos:")
        realms.forEach { realm ->
            val world = core.realms.world(realm.id)
            val here = ctx.player?.let { core.realms.mayEnter(it, realm.id) } ?: true
            val state = when {
                world == null -> "<red>failed to generate"
                here -> "<green>you may enter"
                else -> "<dark_gray>closed to you"
            }
            ctx.reply("<dark_gray>  · <white>${realm.id} <dark_gray>(${realm.kind}) — ${realm.displayName} <dark_gray>· $state")
        }
        ctx.reply("<dark_gray><i>  /mythos realm <id> <dark_gray><i>to go — if the world will have you.")
    }

    @Subcommand("realm", minArgs = 1, usage = "/mythos realm <id>", description = "Go somewhere.")
    fun realm(ctx: CommandContext) {
        val player = ctx.requireSenderPlayer()
        val id = ctx.args[0].lowercase()
        val realm = core.realms.realm(id) ?: return ctx.error("No such realm.")

        // Admins go anywhere. Everyone else is subject to the world's opinion of them.
        if (!player.hasPermission("mythos.admin") && !core.realms.mayEnter(player, id)) {
            return ctx.error(realm.refusal)
        }
        if (!core.realms.send(player, id, "You go to ${realm.displayName}.")) {
            ctx.error("That world did not generate. Check the startup log.")
        }
    }

    @TabComplete(subcommand = "realm")
    fun completeRealm(ctx: CommandContext) = core.realms.realms().map { it.id }

    @Subcommand("scars", description = "Temporary damage to the world, and how to undo it.")
    fun scars(ctx: CommandContext) {
        val scars = core.terraform.scars()
        if (scars.isEmpty()) return ctx.info("The world is as it was. Nothing is pending.")
        ctx.info("Open wounds:")
        scars.forEach { ctx.reply("<dark_gray>  · <white>$it <dark_gray>— ${core.terraform.size(it)} blocks <dark_gray>(/mythos heal $it)") }
    }

    @Subcommand("heal", minArgs = 1, usage = "/mythos heal <scar|all>", description = "Put the world back.")
    fun heal(ctx: CommandContext) {
        val id = ctx.args[0]
        if (id.equals("all", true)) {
            val all = core.terraform.scars()
            all.forEach { core.terraform.heal(it) }
            return ctx.success("Healing ${all.size} scar(s). The world will settle over the next few seconds.")
        }
        if (id !in core.terraform.scars()) return ctx.error("No such scar. /mythos scars")
        core.terraform.heal(id)
        ctx.success("Healing '$id'.")
    }

    @Subcommand("gateways", description = "Every door in the world, and where it goes.")
    fun gateways(ctx: CommandContext) {
        val gateways = core.realms.gateways()
        if (gateways.isEmpty()) return ctx.info("There are no doors. Everything is a command, which is a shame.")
        ctx.info("Doors:")
        gateways.forEach { gate ->
            ctx.reply("<dark_gray>  · <white>${gate.id} <dark_gray>→ ${gate.toRealm} <dark_gray>at ${gate.at.blockX}, ${gate.at.blockY}, ${gate.at.blockZ}")
        }
    }

    @TabComplete(subcommand = "heal")
    fun completeHeal(ctx: CommandContext) = core.terraform.scars() + "all"

    @Subcommand("points", description = "Every extension point anyone has opened or posted to.")
    fun points(ctx: CommandContext) {
        val points = core.extensions.points()
        if (points.isEmpty()) return ctx.info("No addon has opened a point.")
        ctx.info("Extension points:")
        points.forEach { point ->
            ctx.reply("<dark_gray>  · <white>$point <dark_gray>— ${core.extensions.contributions(point).size} contribution(s)")
        }
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

    @TabComplete(subcommand = "reset")
    fun completeReset(ctx: CommandContext) = when (ctx.size) {
        0, 1 -> listOf("world", "story", "player", "powers", "chronicle")
        2 -> Bukkit.getOnlinePlayers().map { it.name } + "confirm"
        else -> listOf("confirm")
    }
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
