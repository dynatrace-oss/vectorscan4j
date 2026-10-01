#include "vs4j_handler.h"
#include <cstdlib>
#include <cstddef>
#include <cstdint>
#include <cstring>

/*
 * Java -> handler, once at construction via configure().
 */
struct CollectMatchConfig {
    int32_t batchSize;
    void   *javaHandler; /* int (*)(void *buffer, int32_t count) */
};

/*
 * handler -> Java, read through a MemoryLayout. buffer is an int64_t rather than a void* so the
 * struct stays mappable by jextract.
 */
struct CollectMatchState {
    int64_t buffer;
    int32_t count;
    int32_t capacity;
};

static_assert(sizeof(CollectMatchState) == 16, "CollectMatchState size changed; update the Java layout");
static_assert(offsetof(CollectMatchState, buffer) == 0, "CollectMatchState::buffer offset changed");
static_assert(offsetof(CollectMatchState, count) == 8, "CollectMatchState::count offset changed");
static_assert(offsetof(CollectMatchState, capacity) == 12, "CollectMatchState::capacity offset changed");

struct MatchEntry {
    int32_t id;
    int32_t from;
    int32_t to;
};

static_assert(sizeof(MatchEntry) == 12, "MatchEntry size changed; update the Java decoder");
static_assert(offsetof(MatchEntry, id) == 0, "MatchEntry::id offset changed");
static_assert(offsetof(MatchEntry, from) == 4, "MatchEntry::from offset changed");
static_assert(offsetof(MatchEntry, to) == 8, "MatchEntry::to offset changed");

class CollectMatch : public vs4j::MatchHandler {
public:
    void configure(void *arg) override {
        const CollectMatchConfig *cfg = static_cast<const CollectMatchConfig *>(arg);
        if (cfg == nullptr || cfg->batchSize < 1) {
            return; /* leaves capacity 0; on_match then refuses to run */
        }
        entries_ = static_cast<MatchEntry *>(malloc((size_t) cfg->batchSize * sizeof(MatchEntry)));
        if (entries_ == nullptr) {
            return;
        }
        javaHandler_    = reinterpret_cast<JavaHandler>(cfg->javaHandler);
        state_.buffer   = reinterpret_cast<int64_t>(entries_);
        state_.capacity = cfg->batchSize;
    }

    int on_match(unsigned int id,
                 unsigned long long from,
                 unsigned long long to,
                 unsigned int flags) override {
        if (state_.capacity == 0 || javaHandler_ == nullptr) {
            return 1; /* unconfigured: stop the scan rather than write through a null buffer */
        }
        MatchEntry *entry = &entries_[state_.count];
        entry->id   = static_cast<int32_t>(id);
        entry->from = static_cast<int32_t>(from);
        entry->to   = static_cast<int32_t>(to);
        ++state_.count;

        if (state_.count == state_.capacity) {
            int result   = javaHandler_(entries_, state_.count);
            state_.count = 0;
            return result;
        }
        return 0;
    }

    void *state() override {
        return &state_;
    }

    ~CollectMatch() override {
        free(entries_);
    }

private:
    typedef int (*JavaHandler)(void *buffer, int32_t count);

    CollectMatchState state_ = {0, 0, 0};
    MatchEntry       *entries_ = nullptr;
    JavaHandler       javaHandler_ = nullptr;
};

VS4J_REGISTER_HANDLER(CollectMatch)

/* ---------- legacy C entry points, still used by Scanner until the Java side migrates ---------- */

struct collect_match_context {
    int32_t* buffer;
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
    ctx->buffer     = static_cast<int32_t *> (realloc(ctx->buffer, (size_t)new_batch_size * 12));
    ctx->batch_size = new_batch_size;
}

int collect_match(unsigned int id,
                  unsigned long long from,
                  unsigned long long to,
                  unsigned int flags,
                  void *context) {
    collect_match_context *ctx = static_cast<collect_match_context *> (context);

    ctx->buffer[3 * ctx->count] = id;
    ctx->buffer[3 * ctx->count + 1] = from;
    ctx->buffer[3 * ctx->count + 2] = to;
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
