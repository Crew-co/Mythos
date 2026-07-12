package net.crewco.mythos.addon

import net.crewco.mythos.MythosPlugin
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader
import java.util.jar.JarFile
import java.util.logging.Level

/**
 * Discovers, loads, enables and unloads addon jars from
 * `plugins/<Host>/addons/`.
 *
 * Load order: addons are topologically sorted by their `depends:` list, so a
 * dependency is always enabled before the addons that need it. Cycles and
 * missing dependencies are reported and those addons are skipped.
 */
class AddonManager(private val plugin: MythosPlugin) {

    /** `plugins/<Host>/addons/` — created on first run. */
    val addonsFolder: File = File(plugin.dataFolder, "addons").apply { mkdirs() }

    private val loaded = LinkedHashMap<String, LoadedAddon>()

    class LoadedAddon(
        val description: AddonDescription,
        val instance: Addon,
        val context: HostAddonContext,
        val classLoader: AddonClassLoader,
    )

    fun loadedAddons(): List<AddonDescription> = loaded.values.map { it.description }

    fun addon(name: String): Addon? = loaded[name.lowercase()]?.instance

    /** Scan the folder, then construct + enable everything in dependency order. */
    fun loadAll() {
        val jars = addonsFolder.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.toList().orEmpty()
        if (jars.isEmpty()) {
            plugin.logger.info("No addons found in ${addonsFolder.path}")
            return
        }

        // 1. Read every addon.yml first, so we know the dependency graph up front.
        val candidates = LinkedHashMap<String, Pair<File, AddonDescription>>()
        for (jar in jars) {
            try {
                val description = readDescription(jar) ?: continue
                if (description.apiVersion != HostApi.ADDON_API_VERSION) {
                    plugin.logger.warning(
                        "Skipping ${description.name}: api-version ${description.apiVersion} " +
                            "but this host provides ${HostApi.ADDON_API_VERSION}.",
                    )
                    continue
                }
                val key = description.name.lowercase()
                if (candidates.containsKey(key)) {
                    plugin.logger.warning("Skipping duplicate addon named ${description.name} (${jar.name}).")
                    continue
                }
                candidates[key] = jar to description
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "Failed to read addon ${jar.name}", e)
            }
        }

        // 2. Enable in dependency order.
        for (key in resolveOrder(candidates)) {
            val (jar, description) = candidates[key] ?: continue
            try {
                enable(jar, description)
            } catch (e: Throwable) {
                plugin.logger.log(Level.SEVERE, "Failed to enable addon ${description.name}", e)
            }
        }

        plugin.logger.info("Loaded ${loaded.size} addon(s): ${loaded.values.joinToString { it.description.name }}")
    }

    /** Disable every addon (reverse order) and release their classloaders. */
    fun unloadAll() {
        for (entry in loaded.values.reversed()) {
            try {
                entry.instance.onDisable()
            } catch (e: Throwable) {
                plugin.logger.log(Level.SEVERE, "Error disabling ${entry.description.name}", e)
            }
            // Undo anything the addon registered (listeners, commands, tasks).
            entry.context.cleanup()
            runCatching { entry.classLoader.close() }
        }
        loaded.clear()
    }

    /** Disable everything, then rescan the folder and load again. */
    fun reload() {
        unloadAll()
        loadAll()
    }

    // ---- internals ----------------------------------------------------------

    private fun enable(jar: File, description: AddonDescription) {
        val classLoader = AddonClassLoader(jar, javaClass.classLoader, description.name)

        // Wire up `depends:` BEFORE any class is loaded. Dependencies are always
        // already enabled (that's what the topological sort is for), so an addon can
        // compile against — and at runtime resolve — classes published by another
        // addon's jar, with a single shared class object for each.
        for (dependency in description.depends) {
            val loaded = loaded[dependency.lowercase()] ?: continue
            classLoader.addDependency(loaded.classLoader)
        }

        val mainClass = Class.forName(description.main, true, classLoader)

        require(Addon::class.java.isAssignableFrom(mainClass)) {
            "${description.main} does not implement Addon"
        }

        val instance = mainClass.getDeclaredConstructor().newInstance() as Addon
        val context = HostAddonContext(plugin, this, description)

        instance.context = context
        instance.onEnable()

        loaded[description.name.lowercase()] = LoadedAddon(description, instance, context, classLoader)
        plugin.logger.info("Enabled addon ${description.name} v${description.version}")
    }

    /** Read `addon.yml` from the jar root without loading any classes. */
    private fun readDescription(jar: File): AddonDescription? {
        JarFile(jar).use { file ->
            val entry = file.getJarEntry("addon.yml") ?: run {
                plugin.logger.warning("${jar.name} has no addon.yml — skipping.")
                return null
            }
            val yaml = file.getInputStream(entry).use { stream ->
                YamlConfiguration.loadConfiguration(InputStreamReader(stream))
            }
            val name = yaml.getString("name") ?: return warn(jar, "missing 'name'")
            val main = yaml.getString("main") ?: return warn(jar, "missing 'main'")
            return AddonDescription(
                name = name,
                version = yaml.getString("version") ?: "0.0.0",
                main = main,
                apiVersion = yaml.getInt("api-version", -1),
                authors = yaml.getStringList("authors"),
                description = yaml.getString("description") ?: "",
                depends = yaml.getStringList("depends"),
            )
        }
    }

    private fun warn(jar: File, why: String): AddonDescription? {
        plugin.logger.warning("${jar.name}: addon.yml $why — skipping.")
        return null
    }

    /**
     * Topological sort by `depends`. Returns keys in a safe enable order,
     * skipping addons with missing dependencies or dependency cycles.
     */
    private fun resolveOrder(candidates: Map<String, Pair<File, AddonDescription>>): List<String> {
        val ordered = ArrayList<String>()
        val visiting = HashSet<String>()
        val done = HashSet<String>()

        fun visit(key: String): Boolean {
            if (key in done) return true
            if (key in visiting) {
                plugin.logger.warning("Dependency cycle involving '$key' — skipping.")
                return false
            }
            val description = candidates[key]?.second ?: return false
            visiting += key
            for (dependency in description.depends) {
                val dependencyKey = dependency.lowercase()
                if (dependencyKey !in candidates) {
                    plugin.logger.warning("${description.name} requires missing addon '$dependency' — skipping.")
                    visiting -= key
                    return false
                }
                if (!visit(dependencyKey)) {
                    visiting -= key
                    return false
                }
            }
            visiting -= key
            done += key
            ordered += key
            return true
        }

        candidates.keys.forEach { visit(it) }
        return ordered
    }
}
