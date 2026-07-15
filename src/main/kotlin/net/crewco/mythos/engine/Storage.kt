package net.crewco.mythos.engine

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

/**
 * Flat YAML. Deliberately boring: no database to stand up, no driver to shade,
 * and a server owner can open `state.yml` and see who Zeus is.
 *
 * Writes go through the async scheduler (see [MythosEngine]) — never touch
 * the disk from a region thread.
 */
class Storage(private val dataFolder: File) {

    private val playersDir = File(dataFolder, "players").apply { mkdirs() }
    private val stateFile = File(dataFolder, "state.yml")

    fun loadState(): YamlConfiguration =
        if (stateFile.exists()) YamlConfiguration.loadConfiguration(stateFile) else YamlConfiguration()

    @Synchronized
    fun saveState(state: YamlConfiguration) {
        state.save(stateFile)
    }

    fun playerFile(uuid: UUID) = File(playersDir, "$uuid.yml")

    fun loadPlayer(uuid: UUID): YamlConfiguration {
        val file = playerFile(uuid)
        return if (file.exists()) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
    }

    @Synchronized
    fun savePlayer(uuid: UUID, yaml: YamlConfiguration) {
        yaml.save(playerFile(uuid))
    }

    fun deleteState() {
        if (stateFile.exists()) stateFile.delete()
    }

    fun knownPlayers(): List<UUID> =
        playersDir.listFiles { f -> f.name.endsWith(".yml") }
            ?.mapNotNull { runCatching { UUID.fromString(it.nameWithoutExtension) }.getOrNull() }
            .orEmpty()
}
