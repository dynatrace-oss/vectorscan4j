#include <iostream>

struct context {
    int32_t* buffer;
    int32_t count;
    int32_t capacity;
};

typedef context context_t;

extern "C" {
    context_t *init() {
        context_t *ctx = static_cast<context_t *>(malloc(sizeof(context_t)));
        ctx->count = 0;
        ctx->capacity = 1;
        ctx->buffer = static_cast<int32_t *> (malloc(3 * ctx->capacity * sizeof(int32_t)));
        return ctx;
    }

    int on_match(unsigned int id,
                unsigned long long from,
                unsigned long long to,
                unsigned int flags,
                context_t *ctx) {
        if (ctx->count >= ctx->capacity) {
            ctx->capacity *= 2;
            ctx->buffer = static_cast<int32_t *> (realloc(ctx->buffer, 3 * ctx->capacity * sizeof(int32_t)));
        }
        ctx->buffer[3 * ctx->count] = id;
        ctx->buffer[3 * ctx->count + 1] = from;
        ctx->buffer[3 * ctx->count + 2] = to;
        ctx->count++;
        return 0;
    }

    void close(context_t *ctx) {
        free(ctx->buffer);
        free(ctx);
    }
} // extern "C"

