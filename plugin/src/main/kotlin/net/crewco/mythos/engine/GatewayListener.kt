package net.crewco.mythos.engine

import net.crewco.mythos.api.realm.Gateway
import net.crewco.mythos.command.CommandContext.Companion.mm
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * **Doors.**
 *
 * `/mythos realm underworld` is an admin command, not a mythology. The dead do not type; Heracles
 * did not type. There has to be a cave, in a place, that a person can walk to.
 *
 * A gateway is checked on the player's own region — never by reading a foreign player's location —
 * and refusal is the interesting half: a mortal can *find* the mouth of the Underworld. It simply
 * won't take them. That's what makes it frightening rather than administrative.
 */
class Gateways(private val core: MythosEngine, private val file: File) {

    private val gateways = ConcurrentHashMap<String, Gateway>()

    /** Don't say "you cannot pass" thirty times a second at someone standing on it. */
    private val lastRefusal = ConcurrentHashMap<UUID, Long>()

    fun open(gateway: Gateway) {
        gateways[gateway.id] = gateway
        save()
        core.logger.info("Gateway '${gateway.id}' → ${gateway.toRealm}")
    }

    fun close(id: String) {
        gateways.remove(id)
        save()
    }

    fun all(): List<Gateway> = gateways.values.toList()

    fun start() {
        core.schedulers.globalRepeating(40, 20) {
            if (gateways.isEmpty()) return@globalRepeating

            Bukkit.getOnlinePlayers().forEach { player ->
                core.schedulers.entity(player) {
                    val here = player.location
                    val gate = gateways.values.firstOrNull { gateway ->
                        gateway.at.world?.uid == here.world.uid &&
                            here.distanceSquared(gateway.at) <= gateway.radius * gateway.radius
                    } ?: return@entity

                    // Both gates: the destination's rules, and the door's own.
                    val allowed = core.realms.mayEnter(player, gate.toRealm) &&
                        gate.requires.mayEnter(player, core.realms.contextFor(player))

                    if (allowed) {
                        core.realms.send(player, gate.toRealm, gate.arrival)
                        return@entity
                    }

                    val last = lastRefusal[player.uniqueId] ?: 0
                    if (System.currentTimeMillis() - last < 8000) return@entity
                    lastRefusal[player.uniqueId] = System.currentTimeMillis()
                    player.sendMessage(mm(gate.refusal))
                }
            }
        }

        // The mouth of a gateway looks and sounds like whatever the ADDON said it does. The engine
        // draws it; it does not decide it.
        core.schedulers.globalRepeating(60, 20) {
            gateways.values.forEach { gateway ->
                val at = gateway.at
                if (at.world == null) return@forEach

                core.schedulers.region(at) {
                    gateway.particle?.let { name ->
                        runCatching {
                            at.world.spawnParticle(
                                Particle.valueOf(name),
                                at.clone().add(0.0, 1.0, 0.0),
                                12, 0.4, 0.8, 0.4, 0.01,
                            )
                        }
                    }
                    gateway.sound?.let { id ->
                        if (Math.random() < 0.25) {
                            runCatching {
                                at.world.playSound(
                                    Sound.sound(Key.key(id), Sound.Source.AMBIENT, 0.5f, 0.8f),
                                    at.x, at.y, at.z,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- persistence ---------------------------------------------------------

    fun load() {
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        yaml.getKeys(false).forEach { id ->
            val at = yaml.getLocation("$id.at") ?: return@forEach
            gateways[id] = Gateway(
                id = id,
                at = at,
                toRealm = yaml.getString("$id.to") ?: return@forEach,
                radius = yaml.getDouble("$id.radius", 2.0),
                arrival = yaml.getString("$id.arrival", "")!!,
                refusal = yaml.getString("$id.refusal", "<dark_gray><i>The way is not open to you.")!!,
            )
        }
        core.logger.info("${gateways.size} gateway(s) loaded.")
    }

    @Synchronized
    fun save() {
        val yaml = YamlConfiguration()
        gateways.values.forEach { gateway ->
            yaml.set("${gateway.id}.at", gateway.at)
            yaml.set("${gateway.id}.to", gateway.toRealm)
            yaml.set("${gateway.id}.radius", gateway.radius)
            yaml.set("${gateway.id}.arrival", gateway.arrival)
            yaml.set("${gateway.id}.refusal", gateway.refusal)
        }
        runCatching { yaml.save(file) }
    }

    fun clear() {
        gateways.clear()
        save()
    }
}
