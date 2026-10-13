#include <iostream>
#include <cstddef>
#include <cstdint>
#include <cstdlib>

typedef int (*java_handler_t)(int32_t count);

/* Passed to init() by NativeBatchHandler, which mirrors this layout. */
struct config {
    int32_t        buffer_size;
    java_handler_t java_handler;
};

/* Read by BufferedMatchHandler through context(), which mirrors this layout. */
struct context {
    int32_t       *buffer;
    int32_t        count;
    int32_t        buffer_size;
    java_handler_t java_handler;
};
typedef context context_t;

extern "C" {
    context_t *init(const config *cfg) {
        context_t *ctx = static_cast<context_t *>(malloc(sizeof(context_t)));
        ctx->count = 0;
        ctx->buffer_size = cfg->buffer_size;
        ctx->java_handler = cfg->java_handler;
        ctx->buffer = static_cast<int32_t *>(malloc(3 * sizeof(int32_t) * cfg->buffer_size));
        return ctx;
    }

    int on_match(unsigned int id,
                unsigned long long from,
                unsigned long long to,
                unsigned int flags,
                context_t *ctx) {

        ctx->buffer[3 * ctx->count] = id;
        ctx->buffer[3 * ctx->count + 1] = from;
        ctx->buffer[3 * ctx->count + 2] = to;
        ctx->count++;

        if (ctx->count == ctx->buffer_size) {
            int result = ctx->java_handler(ctx->count);
            ctx->count = 0;
            return result;
        }
        return 0;
    }

    void close(context_t *ctx) {
        free(ctx->buffer);
        free(ctx);
    }
}
