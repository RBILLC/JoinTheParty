// synccore_testing.h — introspection hooks for core/tests only.
// Deliberately NOT installed with the public headers: shells must never
// depend on these symbols.
#ifndef SYNCCORE_TESTING_H
#define SYNCCORE_TESTING_H

#include <stdint.h>

#include "synccore/synccore.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Reads the capture-path counters: total frames drained by the worker and
 * blocks dropped because the ring was full. */
void sc_test_stats(sc_session_t*, uint64_t* frames_consumed,
                   uint64_t* overrun_blocks);

#ifdef __cplusplus
}
#endif

#endif  // SYNCCORE_TESTING_H
