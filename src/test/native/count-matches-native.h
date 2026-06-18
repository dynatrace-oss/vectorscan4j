/*
 Copyright 2026 JKU/Dynatrace Co-Innovation Lab
 Licensed under the Apache License, Version 2.0.

 Versioned native match-event callback for vectorscan4j tests/benchmarks.
 Bumping the ABI requires renaming the exported symbol (e.g. _v2).
*/
#ifndef VS4J_COUNT_MATCHES_NATIVE_H
#define VS4J_COUNT_MATCHES_NATIVE_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Versioned vectorscan match_event_handler that increments a single 64-bit counter.
 *
 * The `context` pointer must point at a writable `unsigned long long`; on every
 * match its value is incremented by one. Pass a NULL context to no-op (useful
 * for ignoring matches without allocating a counter).
 *
 * Signature (must match `match_event_handler` from hs.h exactly):
 *   int (unsigned int id,
 *        unsigned long long from,
 *        unsigned long long to,
 *        unsigned int flags,
 *        void *context);
 *
 * Returns 0 to continue scanning (this implementation never terminates early).
 */
int vs4j_count_matches_v1(unsigned int id,
                          unsigned long long from,
                          unsigned long long to,
                          unsigned int flags,
                          void *context);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* VS4J_COUNT_MATCHES_NATIVE_H */

