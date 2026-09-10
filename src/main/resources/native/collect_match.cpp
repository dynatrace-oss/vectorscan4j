#include <cstdlib>
#include <cstddef>
#include <cstdint>
#include <cstring>

struct collect_match_context {
    void    *buffer;
    int32_t  count;
    int32_t  batch_size;
    int     (*java_handler)(void *buffer, int32_t count);
};

static_assert(offsetof(collect_match_context, buffer)       ==  0, "buffer offset changed");
static_assert(offsetof(collect_match_context, count)        ==  8, "count offset changed");
static_assert(offsetof(collect_match_context, batch_size)   == 12, "batch_size offset changed");
static_assert(offsetof(collect_match_context, java_handler) == 16, "java_handler offset changed");

extern "C" {

void alloc_context(void *java_handler, collect_match_context **ctx_out) {
    auto *ctx = static_cast<collect_match_context *>(malloc(sizeof(collect_match_context)));
    ctx->buffer       = nullptr;
    ctx->count        = 0;
    ctx->batch_size   = 0;
    ctx->java_handler = reinterpret_cast<int(*)(void *, int32_t)>(java_handler);
    *ctx_out = ctx;
}

void resize_buffer(collect_match_context *ctx, int32_t new_batch_size) {
    ctx->buffer     = realloc(ctx->buffer, (size_t)new_batch_size * 20);
    ctx->batch_size = new_batch_size;
}

int collect_match(unsigned int id,
                  unsigned long long from,
                  unsigned long long to,
                  unsigned int flags,
                  void *context) {
    auto *ctx = static_cast<collect_match_context *>(context);

    char    *entry  = static_cast<char *>(ctx->buffer) + (size_t)ctx->count * 20;
    int32_t  id32   = static_cast<int32_t>(id);
    int64_t  from64 = static_cast<int64_t>(from);
    int64_t  to64   = static_cast<int64_t>(to);
    memcpy(entry,      &id32,   4);
    memcpy(entry + 4,  &from64, 8);
    memcpy(entry + 12, &to64,   8);
    ctx->count++;

    if (ctx->count == ctx->batch_size) {
        int result = ctx->java_handler(ctx->buffer, ctx->count);
        ctx->count = 0;
        return result;
    }
    return 0;
}

void free_context(collect_match_context *ctx) {
    free(ctx->buffer);
    free(ctx);
}
} // extern "C"
