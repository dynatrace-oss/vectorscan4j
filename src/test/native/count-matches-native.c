/*
 Copyright 2026 JKU/Dynatrace Co-Innovation Lab
 Licensed under the Apache License, Version 2.0.
*/
#include "count-matches-native.h"

int vs4j_count_matches_v1(unsigned int id,
                          unsigned long long from,
                          unsigned long long to,
                          unsigned int flags,
                          void *context) {
    if (context == 0) {
        /* No context provided: still safe, just nothing to count. */
        return 0;
    }
    unsigned long long *count = (unsigned long long *) context;
    (*count)++;
    return 0;
}

