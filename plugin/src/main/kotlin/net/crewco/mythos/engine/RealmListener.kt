package net.crewco.mythos.engine

import net.crewco.mythos.command.CommandContext.Companion.mm
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.potion.PotionEffect

/**
 * The worlds enforce themselves.
 *
 * Olympus is not "a build with a warning sign". A mortal who finds a way up onto it is put
 * back down, by the world, immediately — which is the only way "the gods live somewhere you
 * don't" is ever going to be true on a server where players can build a dirt tower.
 */
class RealmListener(private val core: MythosEngine) : Listener {

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        val player = event.player
        val realm = core.realms.realmOf(player.world) ?: return

        if (!core.realms.mayEnter(player, realm.id)) {
            val back = core.realms.spawnOf("gaia")
                ?: Bukkit.getWorlds().first().spawnLocation

            player.teleportAsync(back).thenRun {
                core.schedulers.entity(player) { player.sendMessage(mm(realm.refusal)) }
            }
            return
        }

        core.schedulers.entity(player) {
            if (realm.flight) {
                player.allowFlight = true
                player.isFlying = true
            }
            realm.entryLore.forEach { player.sendMessage(mm(it)) }
        }
    }

    /** The Void is cold. Tartarus is worse. Standing somewhere should feel like something. */
    fun startAmbient() {
        core.schedulers.globalRepeating(100, 100) {
            Bukkit.getOnlinePlayers().forEach { player ->
                val realm = core.realms.realmOf(player.world) ?: return@forEach
                if (realm.ambient.isEmpty()) return@forEach

                core.schedulers.entity(player) {
                    realm.ambient.forEach { effect ->
                        player.addPotionEffect(PotionEffect(effect, 140, 0, true, false, false))
                    }
                }
            }
        }
    }
}
