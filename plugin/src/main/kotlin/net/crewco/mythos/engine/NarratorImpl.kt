package net.crewco.mythos.engine

import net.crewco.mythos.api.story.Beat
import net.crewco.mythos.api.story.NarratorService
import net.crewco.mythos.command.CommandContext.Companion.mm
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scenes, on a timer.
 *
 * Every beat is a separate delayed task on the global region, at a cumulative offset —
 * so a nine-beat epilogue is nine tiny tasks, not a thread sleeping for twenty seconds
 * while a region waits on it.
 */
class NarratorImpl(private val engine: MythosEngine) : NarratorService {

    private val playing = AtomicInteger(0)

    override val isTelling: Boolean get() = playing.get() > 0

    override fun tell(beats: List<Beat>): Long = tell(Bukkit.getServer() as Audience, beats)

    override fun tell(player: Player, beats: List<Beat>): Long = tell(player as Audience, beats)

    override fun tell(audience: Audience, beats: List<Beat>): Long {
        if (beats.isEmpty()) return 0

        var offset = 0L
        playing.incrementAndGet()

        beats.forEach { beat ->
            offset += beat.delayTicks
            engine.schedulers.globalDelayed(offset) { play(audience, beat) }
        }

        engine.schedulers.globalDelayed(offset + 1) { playing.decrementAndGet() }
        return offset
    }

    private fun play(audience: Audience, beat: Beat) {
        beat.sound?.let { id ->
            runCatching { audience.playSound(Sound.sound(Key.key(id), Sound.Source.MASTER, 1f, 1f)) }
        }
        beat.text?.let { audience.sendMessage(mm(it)) }

        if (beat.title != null || beat.subtitle != null) {
            audience.showTitle(
                Title.title(
                    mm(beat.title ?: ""),
                    mm(beat.subtitle ?: ""),
                    Title.Times.times(Duration.ofMillis(600), Duration.ofSeconds(3), Duration.ofMillis(900)),
                ),
            )
        }
        // A cheap, extremely effective blackout: blindness for the length of the beat.
        if (beat.darkness && audience is Player) {
            engine.schedulers.entity(audience) {
                audience.addPotionEffect(
                    org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS, 100, 0, false, false),
                )
            }
        }
    }

    /** Blackout for everyone — used by era transitions, where `audience` is the server. */
    fun darkenEveryone(ticks: Int) {
        Bukkit.getOnlinePlayers().forEach { player ->
            engine.schedulers.entity(player) {
                player.addPotionEffect(
                    org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.BLINDNESS, ticks, 0, false, false,
                    ),
                )
            }
        }
    }
}
