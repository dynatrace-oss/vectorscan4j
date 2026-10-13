#include <iostream>
#include <cstddef>
#include <cstdint>

extern "C" {
    // called during NativeMatchHandler's constructor
    int32_t * init() {
        int32_t *ctx = static_cast<int32_t *>(malloc(sizeof(int32_t)));
        (*ctx) = 0;
        return ctx;
    }

    // the native on match event
    int on_match(unsigned int id,
                unsigned long long from,
                unsigned long long to,
                unsigned int flags,
                int32_t *context) {
        (*context)++;
        return 0;
    }

    // called in NativeMatchHandler's destructor
    void close(int32_t *ctx) {
        free(ctx);
    }
} // extern "C"

