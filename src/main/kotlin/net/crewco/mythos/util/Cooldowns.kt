package net.crewco.mythos.util

import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe per-key cooldowns (typically keyed by player [UUID]).
 *
 * Backed by a [ConcurrentHashMap]; [test] uses an atomic `compute` so a
 * check-and-start can't race even if the same player triggers it from two
 * region threads at once. The clock is injectable so this class can be unit
 * tested without waiting in real time.
 */
class Cooldowns(private val nanoClock: () -> Long = System::nanoTime) {

    private val expiryNanos = ConcurrentHashMap<UUID, Long>()

    /**
     * If [id] is off cooldown, start a new [duration] cooldown and return true
     * (the action is allowed). Otherwise return false. Atomic.
     */
    fun test(id: UUID, duration: Duration): Boolean {
        val now = nanoClock()
        var allowed = false
        expiryNanos.compute(id) { _, existing ->
            if (existing == null || existing <= now) {
                allowed = true
                now + duration.toNanos()
            } else {
                existing
            }
        }
        return allowed
    }

    /** Time left on [id]'s cooldown, or [Duration.ZERO] if none. */
    fun remaining(id: UUID): Duration {
        val expiry = expiryNanos[id] ?: return Duration.ZERO
        val diff = expiry - nanoClock()
        return if (diff <= 0) Duration.ZERO else Duration.ofNanos(diff)
    }

    /** Whole seconds left (rounded up) — handy for messages. */
    fun remainingSeconds(id: UUID): Long {
        val nanos = remaining(id).toNanos()
        return if (nanos <= 0) 0 else (nanos + 1_000_000_000L - 1) / 1_000_000_000L
    }

    fun clear(id: UUID) {
        expiryNanos.remove(id)
    }
}
