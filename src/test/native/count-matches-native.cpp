#include "vs4j_handler.h"
#include <cstddef>
#include <cstdint>

struct CounterState {
    uint64_t matches;
    uint64_t lastEnd;
};

static_assert(sizeof(CounterState) == 16, "CounterState size changed; update NativeMatchHandlerTests");
static_assert(offsetof(CounterState, matches) == 0, "CounterState::matches offset changed");
static_assert(offsetof(CounterState, lastEnd) == 8, "CounterState::lastEnd offset changed");

class Counter : public vs4j::MatchHandler {
public:
    int on_match(unsigned int id,
                 unsigned long long from,
                 unsigned long long to,
                 unsigned int flags) override {
        ++state_.matches;
        state_.lastEnd = to;
        return 0;
    }

    void *state() override {
        return &state_;
    }

private:
    CounterState state_ = {0, 0};
};

VS4J_REGISTER_HANDLER(Counter)

/*
 * "stateless" is deliberately hand-rolled rather than registered, exposing only _on_match. It
 * covers the path where _init and _free are absent and the handler scans with a NULL context.
 */
extern "C" int stateless_on_match(unsigned int id,
                                  unsigned long long from,
                                  unsigned long long to,
                                  unsigned int flags,
                                  void *context) {
    return 0;
}
