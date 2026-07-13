package net.crewco.mythos.api.story

/**
 * What has happened, in order, forever. `mythos.chronicle`.
 *
 * The engine writes to it on its own — every claim, death, objective and turning of an
 * age — and a story addon adds the things only it knows are important ("Gaia forged
 * something underground; nobody saw it but the Earth").
 *
 * This is the worldbuilding payload. A player who joins in the Trojan War can read what
 * the Titans did to each other four ages ago, and *who* did it, by name. The server
 * accumulates a history that no one wrote in advance.
 */
data class ChronicleEntry(
    val at: Long,
    /** The era it happened in. */
    val era: String,
    /** A coarse category: "era", "claim", "death", "objective", "story". Yours can be anything. */
    val kind: String,
    /** MiniMessage. Written once, read for years. Write it like it matters. */
    val text: String,
)

interface ChronicleService {

    /** Record something. Use the past tense; it's a history book. */
    fun record(kind: String, text: String)

    /** Most recent first. */
    fun entries(limit: Int = 50): List<ChronicleEntry>

    fun entriesOfEra(eraId: String, limit: Int = 100): List<ChronicleEntry>

    fun size(): Int
}
