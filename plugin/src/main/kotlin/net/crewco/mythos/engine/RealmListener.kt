package net.crewco.mythos.engine

import net.crewco.mythos.command.CommandContext.Companion.mm
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import org.bukkit.Bukkit
import org.bukkit.Particle
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
            // Back where they came from. The engine used to send them to a realm called "gaia",
            // which is a word from a story — the engine has no business knowing it, and a server
            // running a Norse mythology on this plugin would have been thrown into a wall.
            val back = event.from.spawnLocation.takeIf { it.world != null }
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

    /**
     * The Void is cold. Tartarus is worse. **Standing somewhere should feel like something.**
     *
     * Effects, a sound now and then, and drifting particles — all on the player's own region, all
     * cheap, and worth more to the feeling of a place than any amount of chat text. A world that is
     * silent is a world that is a *level*.
     */
    fun startAmbient() {
        core.schedulers.globalRepeating(100, 100) {
            Bukkit.getOnlinePlayers().forEach { player ->
                val realm = core.realms.realmOf(player.world) ?: return@forEach

                core.schedulers.entity(player) {
                    realm.ambient.forEach { effect ->
                        player.addPotionEffect(PotionEffect(effect, 140, 0, true, false, false))
                    }

                    // Not every tick, and not on a timer everyone can predict — a place that
                    // groans on a five-second metronome is a machine, not a place.
                    if (Math.random() < 0.35) {
                        realm.ambientSound?.let { id ->
                            runCatching {
                                player.playSound(
                                    Sound.sound(Key.key(id), Sound.Source.AMBIENT, 0.45f, 0.6f + Math.random().toFloat() * 0.3f),
                                )
                            }
                        }
                    }
                    realm.ambientParticle?.let { name ->
                        runCatching {
                            val particle = Particle.valueOf(name)
                            player.world.spawnParticle(
                                particle,
                                player.location.clone().add(0.0, 1.5, 0.0),
                                8, 3.0, 2.0, 3.0, 0.0,
                            )
                        }
                    }
                }
            }
        }
    }
}
