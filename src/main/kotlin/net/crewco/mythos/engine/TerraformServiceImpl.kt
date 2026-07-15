package net.crewco.mythos.engine

import net.crewco.mythos.api.world.Scar
import net.crewco.mythos.api.world.TerraformService
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Undo, for the world.
 *
 * Persisted, because a server that crashes halfway through the flood must not wake up permanently
 * underwater — and healing is spread across ticks and across regions, because putting two hundred
 * thousand blocks back in one go is how you make a Folia server stop answering.
 */
class TerraformServiceImpl(private val core: MythosEngine, private val file: File) : TerraformService {

    private data class Record(val world: UUID, val x: Int, val y: Int, val z: Int, val was: Material)

    private val scars = ConcurrentHashMap<String, CopyOnWriteArrayList<Record>>()

    private inner class ScarImpl(override val id: String) : Scar {

        private val records get() = scars.getOrPut(id) { CopyOnWriteArrayList() }

        override fun set(block: Block, material: Material) {
            if (block.type == material) return
            if (records.size >= LIMIT) {
                core.logger.warning("Scar '$id' hit its ${LIMIT}-block limit; further changes are permanent.")
                block.type = material
                return
            }
            // Remember first, THEN change. The other order is a bug you only find at 200,000 blocks.
            records += Record(block.world.uid, block.x, block.y, block.z, block.type)
            block.type = material
        }

        override fun set(location: Location, material: Material) = set(location.block, material)

        override fun size(): Int = records.size

        override fun forget() {
            scars.remove(id)
            core.schedulers.async { save() }
        }
    }

    override fun scar(id: String): Scar = ScarImpl(id)

    override fun scars(): List<String> = scars.keys.toList()

    override fun size(id: String): Int = scars[id]?.size ?: 0

    override fun heal(id: String) {
        val records = scars.remove(id) ?: return
        core.logger.info("Healing scar '$id' — ${records.size} blocks.")

        // Newest first, so overlapping edits unwind in the order they were made. Each block is put
        // back on the region that owns it, and the whole thing is dribbled out over ticks: the
        // waters go down over a few seconds, which is also how they should look.
        records.reversed().chunked(BATCH).forEachIndexed { index, batch ->
            core.schedulers.globalDelayed(index.toLong() * 2) {
                batch.forEach { record ->
                    val world = Bukkit.getWorld(record.world) ?: return@forEach
                    val location = Location(world, record.x.toDouble(), record.y.toDouble(), record.z.toDouble())
                    core.schedulers.region(location) {
                        location.block.type = record.was
                    }
                }
            }
        }
        core.schedulers.async { save() }
    }

    // ---- persistence ---------------------------------------------------------

    fun load() {
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        yaml.getKeys(false).forEach { id ->
            val list = CopyOnWriteArrayList<Record>()
            yaml.getStringList(id).forEach { line ->
                val parts = line.split(";")
                if (parts.size != 5) return@forEach
                runCatching {
                    list += Record(
                        UUID.fromString(parts[0]),
                        parts[1].toInt(), parts[2].toInt(), parts[3].toInt(),
                        Material.valueOf(parts[4]),
                    )
                }
            }
            if (list.isNotEmpty()) scars[id] = list
        }
        if (scars.isNotEmpty()) {
            core.logger.info("Unhealed scars on the world: ${scars.keys.joinToString()} (${scars.values.sumOf { it.size }} blocks)")
        }
    }

    @Synchronized
    fun save() {
        val yaml = YamlConfiguration()
        scars.forEach { (id, records) ->
            yaml.set(id, records.map { "${it.world};${it.x};${it.y};${it.z};${it.was.name}" })
        }
        runCatching { yaml.save(file) }
            .onFailure { core.logger.warning("Could not write scars.yml: ${it.message}") }
    }

    private companion object {
        /** Beyond this, a "temporary" change is really a decision. */
        const val LIMIT = 250_000

        /** Blocks put back per tick-batch. */
        const val BATCH = 400
    }
}
