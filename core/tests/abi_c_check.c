/* abi_c_check.c — the public header must stay consumable as plain C99.
 *
 * Compiled as a standalone C translation unit (see core/CMakeLists.txt's
 * synccore_abi_c_check target) so any C++-only construct accidentally
 * introduced into synccore.h fails the build immediately. Previously this
 * was a one-line file generated inline by CMakeLists.txt
 * (`int main(void) { return (int)SC_OK; }`); it is now checked in so it can
 * carry real coverage for new ABI surface as it's added.
 *
 * CTL-01a (technical-requirements.md §2.9) coverage: SC_EVT_ACTIVE_PROBE
 * must compile as a case in an exhaustive switch over sc_event_type_t,
 * sc_evt_active_probe_t must be a valid C aggregate, and
 * sc_notify_probe_executed must link and behave per its documented
 * contract — SC_OK on a live session, and safely ignored (still SC_OK)
 * with no probe outstanding. The full sentinel/turn-off/verdict decision
 * logic is exercised at the policy level (core/tests/test_policy.cpp);
 * this file is compile/link/basic-contract coverage only.
 *
 * DSP-03a (technical-requirements.md §2.12) coverage: same shape, for
 * SC_EVT_ACTIVE_DUCK / sc_evt_active_duck_t / sc_notify_duck_executed.
 *
 * CTL-06/W1 (technical-requirements.md §2.17) coverage: same shape again,
 * for SC_EVT_POLICY_STATE / sc_evt_policy_state_t and SC_EVT_FIX_DIAG /
 * sc_evt_fix_diag_t / sc_fix_diag_verdict_t. Both are diagnostic-only —
 * emitted by the worker off existing cadences/call sites, with no new
 * sc_notify_ or sc_ entry point of their own — so this file's job for them is
 * exhaustiveness plus "these are valid C99 aggregates with the documented
 * field shapes," mirroring the ACTIVE_PROBE/ACTIVE_DUCK coverage above.
 */
#include <synccore/synccore.h>

/* Exhaustive switch: a missing enumerator here would warn under
 * -Wswitch (this target builds with -Wall -Wextra like the rest of the
 * tree), which is the point — every sc_event_type_t value, including the
 * newest one, must be nameable from plain C99. */
static int event_is_known(sc_event_type_t type) {
    switch (type) {
        case SC_EVT_SYNC_ESTIMATE:
        case SC_EVT_CORRECTION:
        case SC_EVT_REQUEST_FIX:
        case SC_EVT_FIX_REJECTED:
        case SC_EVT_TRACK_LOST:
        case SC_EVT_CALIBRATION_RESULT:
        case SC_EVT_LATENCY_RESIDUAL:
        case SC_EVT_ACTIVE_PROBE:
        case SC_EVT_ACTIVE_DUCK:
        case SC_EVT_POLICY_STATE:
        case SC_EVT_FIX_DIAG:
            return 1;
    }
    return 0;
}

/* Same exhaustiveness discipline, for the new verdict enum. */
static int fix_diag_verdict_is_known(sc_fix_diag_verdict_t v) {
    switch (v) {
        case SC_FIX_DIAG_ACCEPTED:
        case SC_FIX_DIAG_SELF_HEARING:
        case SC_FIX_DIAG_LOW_CONFIDENCE:
        case SC_FIX_DIAG_SETTLING:
            return 1;
    }
    return 0;
}

int main(void) {
    sc_evt_active_probe_t probe;
    probe.pause_ms = 200;
    if (probe.pause_ms != 200) return 1;
    if (!event_is_known(SC_EVT_ACTIVE_PROBE)) return 1;

    sc_evt_active_duck_t duck;
    duck.duck_ms = 150;
    if (duck.duck_ms != 150) return 1;
    if (!event_is_known(SC_EVT_ACTIVE_DUCK)) return 1;

    sc_evt_policy_state_t policy_state;
    policy_state.settled = 1;
    policy_state.in_deadband_streak = 3;
    if (!policy_state.settled) return 1;
    if (policy_state.in_deadband_streak != 3) return 1;
    if (!event_is_known(SC_EVT_POLICY_STATE)) return 1;

    sc_evt_fix_diag_t fix_diag;
    fix_diag.match_offset_ms = 12345;
    fix_diag.verdict = SC_FIX_DIAG_SELF_HEARING;
    fix_diag.tracks_room = 0;
    fix_diag.tracks_cand = 1;
    fix_diag.room_anchor_offset_ms = -1;
    fix_diag.room_anchor_age_ms = -1;
    fix_diag.off = 100.0;
    fix_diag.predicted_room = 200.0;
    fix_diag.local_audible_ms = 100.5;
    if (fix_diag.match_offset_ms != 12345) return 1;
    if (!fix_diag_verdict_is_known(fix_diag.verdict)) return 1;
    if (fix_diag.tracks_room || !fix_diag.tracks_cand) return 1;
    if (!event_is_known(SC_EVT_FIX_DIAG)) return 1;

    {
        sc_config_t cfg;
        sc_session_t* s = NULL;
        cfg.sample_rate_hz = 48000;
        cfg.channels = 1;
        cfg.initial_route = SC_ROUTE_SPEAKER;
        cfg.output_latency_prior_ms = -1;
        cfg.command_latency_prior_ms = -1;
        cfg.deadband_ms = 0;
        if (sc_create(&cfg, &s) != SC_OK) return 1;

        /* No probe outstanding on a fresh session: documented as safely
         * ignored, still returns SC_OK (mirrors sc_notify_seek_issued's
         * unconditional-enqueue shape). */
        if (sc_notify_probe_executed(s) != SC_OK) return 1;

        /* Same contract for the new duck echo: no duck outstanding on a
         * fresh session, still SC_OK. */
        if (sc_notify_duck_executed(s, 60) != SC_OK) return 1;

        sc_destroy(s);
    }

    return (int)SC_OK;
}
