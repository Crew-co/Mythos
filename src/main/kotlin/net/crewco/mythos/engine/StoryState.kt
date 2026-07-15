package net.crewco.mythos.engine

/**
 * **Whether the story is running, and who decided.**
 *
 * The world used to begin the moment the server did. Now it's a thing someone starts — once — and it
 * runs across every restart until it's stopped or paused. The state is persisted, so a crash in the
 * middle of an age comes back up exactly where it was, with no command needed.
 */
enum class StoryState {
    /** Never started, or stopped. Players wait as themselves; no era runs, no beat can be struck. */
    IDLE,

    /** Running. Restart-safe: the engine resumes the current age on boot without being told to. */
    RUNNING,

    /** Held. The current age stays exactly where it is; no beat is struck until it resumes. */
    PAUSED,
}
