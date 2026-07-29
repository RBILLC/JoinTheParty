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
            return 1;
    }
    return 0;
}

int main(void) {
    sc_evt_active_probe_t probe;
    probe.pause_ms = 200;
    if (probe.pause_ms != 200) return 1;
    if (!event_is_known(SC_EVT_ACTIVE_PROBE)) return 1;

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

        sc_destroy(s);
    }

    return (int)SC_OK;
}
