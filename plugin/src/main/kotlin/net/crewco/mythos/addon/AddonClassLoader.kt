package net.crewco.mythos.addon

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One classloader per addon jar.
 *
 * The parent is the HOST's classloader, so `Addon`, `AddonContext`, the command
 * annotations etc. resolve to the *same class objects* the host uses. An addon that
 * shaded its own copy of the API would get a different class with the same name,
 * `isAssignableFrom` would fail, and it wouldn't load. (Never shade the API.)
 *
 * ## Dependency delegation
 *
 * Addon classloaders are otherwise *siblings*: by default MyAddon cannot see a class
 * that lives inside OtherAddon's jar, even with `depends: [ OtherAddon ]`. That makes
 * it impossible for one addon to publish an API that others build on — which is the
 * single most useful thing an addon system can do.
 *
 * So: a class not found in the parent or in our own jar is looked for in the
 * classloaders of the addons we declared in `depends:`. The class is *defined by the
 * dependency's loader*, so there is exactly one copy of it and `instanceof` works
 * across addons — the same guarantee the host gives for the API itself.
 *
 * Lookups only ever reach into a dependency's OWN jar ([findOwnClass]), never back
 * out through its parent or its own dependencies, so this cannot loop.
 */
class AddonClassLoader(
    jar: File,
    parent: ClassLoader,
    /** For error messages. */
    val addonName: String,
) : URLClassLoader(arrayOf<URL>(jar.toURI().toURL()), parent) {

    private val dependencies = CopyOnWriteArrayList<AddonClassLoader>()

    /** Wired by the AddonManager from `depends:`, before the main class is loaded. */
    fun addDependency(loader: AddonClassLoader) {
        if (loader !== this) dependencies += loader
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // Parent (host + Folia + Kotlin stdlib), then our own jar.
        try {
            return super.loadClass(name, resolve)
        } catch (notHere: ClassNotFoundException) {
            // Then whatever we said we depend on.
            for (dependency in dependencies) {
                try {
                    return dependency.findOwnClass(name, resolve)
                } catch (_: ClassNotFoundException) {
                    // try the next dependency
                }
            }
            throw ClassNotFoundException("$name (not in $addonName, its parent, or its dependencies)", notHere)
        }
    }

    /**
     * Our jar and nothing else. Called by addons that depend on us — deliberately
     * does NOT delegate onwards, so dependency lookups are one hop deep and acyclic.
     */
    @Throws(ClassNotFoundException::class)
    fun findOwnClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            val loaded = findLoadedClass(name)
            if (loaded != null) {
                if (resolve) resolveClass(loaded)
                return loaded
            }
            val found = findClass(name) // URLClassLoader.findClass → our URLs only
            if (resolve) resolveClass(found)
            return found
        }
    }

    /** Resources too — a dependency's config templates, lang files, and so on. */
    override fun getResource(name: String): URL? =
        super.getResource(name) ?: dependencies.firstNotNullOfOrNull { it.findResource(name) }
}
