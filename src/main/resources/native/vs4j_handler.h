#ifndef VS4J_HANDLER_H
#define VS4J_HANDLER_H

#include <type_traits>

/**
 * Support header for writing native match handlers for vectorscan4j.
 *
 * The full guide -- the underlying C ABI contract, writing handlers in other languages,
 * lifetime rules, and the constraints summarised on VS4J_REGISTER_HANDLER below -- lives at
 * https://github.com/dynatrace-oss/vectorscan4j/blob/main/docs/native-match-handlers.md
 */
namespace vs4j {

class MatchHandler {
public:
    virtual ~MatchHandler() = default;

    /**
     * Called once per match. Return 0 to continue scanning, non-zero to stop early
     * (vectorscan then returns HS_SCAN_TERMINATED).
     */
    virtual int on_match(unsigned int id,
                         unsigned long long from,
                         unsigned long long to,
                         unsigned int flags) = 0;

    /**
     * Optional. Return a pointer to a standard-layout struct holding this handler's results, and
     * Java can map it with a MemoryLayout and read the fields directly instead of calling back in
     * per field. The default returns null, which Java sees as "this handler exposes no state".
     */
    virtual void *state() {
        return nullptr;
    }
};

} // namespace vs4j

/**
 * Emits Type_init / Type_on_match / Type_free / Type_state, using Type's own name as the symbol
 * prefix.
 * Place this at global scope in the same translation unit as Type.
 *
 * Type must be a class deriving from vs4j::MatchHandler, concrete, and default-constructible;
 * the static_asserts below report each of those. The name must also be a single unqualified
 * identifier, because it is pasted into the symbol names -- register a namespaced class
 * through a global alias (using Q = ns::Q) instead.
 *
 * All three entry points are noexcept: they are called across boundaries that cannot unwind a
 * C++ exception, so an escaping one becomes a deterministic std::terminate rather than
 * undefined behaviour. To stop the scan instead of aborting, catch inside your own on_match
 * and return non-zero.
 */
#define VS4J_REGISTER_HANDLER(Type)                                                    \
    static_assert(std::is_base_of<::vs4j::MatchHandler, Type>::value,                  \
                  #Type " must derive from vs4j::MatchHandler");                       \
    static_assert(!std::is_abstract<Type>::value,                                      \
                  #Type " must implement on_match; it is still abstract");             \
    static_assert(std::is_default_constructible<Type>::value,                          \
                  #Type " must be default-constructible; the macro creates it with new Type()"); \
    extern "C" void *Type##_init(void) noexcept {                                      \
        return new Type();                                                             \
    }                                                                                  \
    extern "C" int Type##_on_match(unsigned int id,                                    \
                                   unsigned long long from,                            \
                                   unsigned long long to,                              \
                                   unsigned int flags,                                 \
                                   void *context) noexcept {                           \
        return static_cast<Type *>(context)->on_match(id, from, to, flags);            \
    }                                                                                  \
    extern "C" void Type##_free(void *context) noexcept {                              \
        delete static_cast<Type *>(context);                                           \
    }                                                                                  \
    extern "C" void *Type##_state(void *context) noexcept {                            \
        return static_cast<Type *>(context)->state();                                  \
    }


#endif /* VS4J_HANDLER_H */
