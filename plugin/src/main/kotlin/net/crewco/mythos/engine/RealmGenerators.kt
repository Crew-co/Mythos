package net.crewco.mythos.engine

import org.bukkit.Material
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import java.util.Random
import kotlin.math.abs

/**
 * **Nothing.**
 *
 * No noise, no surface, no caves, no decoration, no mobs, no structures. A world that
 * generates the absence of a world — which is, for once, exactly what the story asked for.
 *
 * A small platform sits at the origin so that arriving somewhere with no ground isn't
 * instantly fatal for anyone who isn't a spirit.
 */
open class VoidGenerator(
    private val platformMaterial: Material,
    private val platformY: Int,
    private val platformRadius: Int,
) : ChunkGenerator() {

    override fun shouldGenerateNoise() = false
    override fun shouldGenerateSurface() = false
    override fun shouldGenerateCaves() = false
    override fun shouldGenerateDecorations() = false
    override fun shouldGenerateMobs() = false
    override fun shouldGenerateStructures() = false

    override fun generateSurface(world: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, chunk: ChunkData) {
        if (platformRadius <= 0) return

        // Only the chunks that could possibly touch the platform do any work at all.
        val minX = chunkX * 16
        val minZ = chunkZ * 16
        if (abs(minX) > platformRadius + 16 || abs(minZ) > platformRadius + 16) return

        for (x in 0 until 16) {
            for (z in 0 until 16) {
                val worldX = minX + x
                val worldZ = minZ + z
                if (worldX * worldX + worldZ * worldZ <= platformRadius * platformRadius) {
                    chunk.setBlock(x, platformY, z, platformMaterial)
                }
            }
        }
    }
}

/**
 * Olympus: the same nothing, but with an island in it, and the island is where the thrones go.
 *
 * A ring of stairs one block up around the rim, because a mountain with a *lip* reads as a
 * place rather than as a slab, and that difference is most of what "worldbuilding" means.
 */
class OlympusGenerator(
    material: Material,
    y: Int,
    radius: Int,
) : VoidGenerator(material, y, radius) {

    private val rim = radius
    private val height = y
    private val stone = material

    override fun generateSurface(world: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, chunk: ChunkData) {
        super.generateSurface(world, random, chunkX, chunkZ, chunk)

        val minX = chunkX * 16
        val minZ = chunkZ * 16
        if (abs(minX) > rim + 16 || abs(minZ) > rim + 16) return

        for (x in 0 until 16) {
            for (z in 0 until 16) {
                val worldX = minX + x
                val worldZ = minZ + z
                val distanceSquared = worldX * worldX + worldZ * worldZ
                val outer = rim * rim
                val inner = (rim - 2) * (rim - 2)
                if (distanceSquared in (inner + 1)..outer) {
                    chunk.setBlock(x, height + 1, z, stone)
                }
            }
        }
    }
}
