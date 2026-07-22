package com.jointheparty.app.ui.session

/**
 * UI-02: authoritative phase state machine (technical-requirements.md
 * §2.4). Legal transitions are enforced centrally in
 * [SessionViewModel.transition] — this enum carries no transition logic of
 * its own.
 */
enum class SessionPhase {
    IDLE,
    LISTENING,
    MATCHING,
    AIMING,
    CONVERGING,
    LOCKED,
    DRIFTING,
    LOST,
    NEEDS_SPOTIFY,
    NEEDS_PREMIUM,
    ERROR,
}
