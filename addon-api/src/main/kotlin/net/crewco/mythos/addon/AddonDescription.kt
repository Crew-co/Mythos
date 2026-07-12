package net.crewco.mythos.addon

/**
 * Parsed from the `addon.yml` at the root of an addon jar:
 *
 * ```yaml
 * name: MyAddon
 * version: 1.0.0
 * main: com.example.myaddon.MyAddon
 * api-version: 1          # must match the host's ADDON_API_VERSION
 * authors: [ You ]
 * description: Does a thing
 * depends: [ OtherAddon ] # optional; loaded before this one
 * ```
 */
data class AddonDescription(
    val name: String,
    val version: String,
    val main: String,
    val apiVersion: Int,
    val authors: List<String> = emptyList(),
    val description: String = "",
    /** Names of other addons that must load first. */
    val depends: List<String> = emptyList(),
)
