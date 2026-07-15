package net.crewco.mythos.engine

import net.crewco.mythos.api.story.ChronicleEntry
import net.crewco.mythos.api.story.ChronicleService
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The history of the world, written as it happens.
 *
 * Nobody plans this file. It accumulates: who was Gaia, who cut the sky, how long the
 * war lasted, which of the sworn died at the wall. Three ages later a player can read it
 * and find out what the server did before they arrived — which is the entire point of a
 * persistent mythology and the cheapest worldbuilding there is.
 */
class ChronicleImpl(private val engine: MythosEngine, private val file: File) : ChronicleService {

    private val entries = CopyOnWriteArrayList<ChronicleEntry>()

    fun load() {
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        yaml.getMapList("entries").forEach { row ->
            entries += ChronicleEntry(
                at = (row["at"] as? Number)?.toLong() ?: 0L,
                era = row["era"] as? String ?: "",
                kind = row["kind"] as? String ?: "story",
                text = row["text"] as? String ?: return@forEach,
            )
        }
        engine.logger.info("Chronicle: ${entries.size} entries. The world remembers.")
    }

    override fun record(kind: String, text: String) {
        entries += ChronicleEntry(System.currentTimeMillis(), engine.eras.currentId(), kind, text)
        engine.schedulers.async { save() }
    }

    override fun entries(limit: Int): List<ChronicleEntry> = entries.reversed().take(limit)

    override fun entriesOfEra(eraId: String, limit: Int): List<ChronicleEntry> =
        entries.filter { it.era == eraId }.reversed().take(limit)

    override fun size(): Int = entries.size

    /** The world never happened. */
    fun clear() {
        entries.clear()
        engine.schedulers.async { save() }
    }

    /** Blocking. Async only. */
    @Synchronized
    fun save() {
        val yaml = YamlConfiguration()
        yaml.set(
            "entries",
            entries.map { mapOf("at" to it.at, "era" to it.era, "kind" to it.kind, "text" to it.text) },
        )
        runCatching { yaml.save(file) }
            .onFailure { engine.logger.warning("Could not write chronicle.yml: ${it.message}") }
    }
}
