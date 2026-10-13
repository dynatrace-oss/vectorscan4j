# Native match handlers

A `NativeMatchHandler` lets vectorscan call your own native function on every match, instead of
calling back up into the JVM. Every match normally costs one native-to-Java upcall; on workloads
that produce a very large number of matches, that overhead can dominate the scan itself. Handling
matches natively removes this overhead.

This is an advanced feature. If your handler needs to do anything substantial with each match in
Java, the upcall is not your bottleneck and a plain
[`MatchHandler`](../src/main/java/com/dynatrace/vectorscan4j/MatchHandler.java) is the right choice.
If you want to reduce upcall overhead without leaving Java, consider
[`BufferedMatchHandler`](../src/main/java/com/dynatrace/vectorscan4j/BufferedMatchHandler.java) first —
it collects matches natively and delivers them a buffer at a time, so you pay one upcall per buffer
rather than one per match.

## Contents

- [The ABI contract](#the-abi-contract)
- [Writing a handler](#writing-a-handler)
- [Building your handler](#building-your-handler)
- [Integrating it into your build](#integrating-it-into-your-build)
- [Using it from Java](#using-it-from-java)
- [Reading results back](#reading-results-back)
- [Lifetime and ownership](#lifetime-and-ownership)
- [Constraints and pitfalls](#constraints-and-pitfalls)
- [Other languages](#other-languages)

## The ABI contract

A `NativeMatchHandler` is given a path to a shared library, and resolves up to three `extern "C"`
symbols from it by their exact names:

| symbol | signature | required |
|---|---|---|
| `init` | `void *(void *)` | no |
| `on_match` | `int (unsigned int, unsigned long long, unsigned long long, unsigned int, void *)` | **yes** |
| `close` | `void (void *)` | no |

Semantics:

- `init` is called once when the `NativeMatchHandler` is constructed, with the pointer passed to the
  two-argument constructor, or `NULL` if none was given. Whatever it returns becomes the `context`
  argument passed to every `on_match` call. If the symbol is absent, the context is `NULL`. See
  [Passing setup data in](#passing-setup-data-in).
- `on_match` is called once per match, with the pattern id, the start and end offsets, the match
  flags, and the context. Return `0` to continue scanning, non-zero to stop early — vectorscan then
  returns `HS_SCAN_TERMINATED`, which the Java side treats as a normal completion.
- `close` is called with the same context when the handler is closed or garbage collected. If the
  symbol is absent, nothing is released.

Because the names are fixed, **a shared library can contain only one handler.** Two handlers in one
library would both export `init`, `on_match` and `close`, and the loader cannot tell them apart. Put
each handler in its own library.

The `on_match` signature is vectorscan's own `match_event_handler`; vectorscan4j passes the symbol
address straight to `hs_scan`, so the signature must match exactly.

This contract is plain C, so a handler can be written in C, C++, or anything else that exports
C symbols — see [Other languages](#other-languages).

## Writing a handler

A handler is a `.cpp` file with three `extern "C"` functions. `init` builds the context from an init
struct that Java passes in, `on_match` updates the context for every match, and `close` releases it.
Start from this template:

```cpp
#include <cstdint>
#include <cstdlib>

/* Passed by Java to init(). Fill in the fields your handler is configured with. */
struct InitStruct {
    int32_t example_setting;
};

/* The state your handler keeps between matches. Java receives it through context(). */
struct Context {
    uint64_t* example_context_state;
};

extern "C" {

Context *init(const InitStruct *init_struct) {
    Context *ctx = static_cast<Context *>(calloc(1, sizeof(Context)));
    return ctx;
}

int on_match(unsigned int id,
             unsigned long long from,
             unsigned long long to,
             unsigned int flags,
             Context *ctx) {
    /* Ready and/or modify data inside the context */
    return 0;                       /* non-zero stops the scan */
}

void close(Context *ctx) {
    /* The native lib is the owner of the Context data, so the JVM will not clean it up for you. */
    free(ctx);
}

} /* extern "C" */

/* Compile error if on_match does not have the signature vectorscan expects. */
static int (*const on_match_check)(unsigned int, unsigned long long, unsigned long long,
                                   unsigned int, Context *) = on_match;
```

Replace `InitStruct` and `Context` with your own types, and fill in `init`, `on_match` and
`close` for your handler. The function names are fixed: `NativeMatchHandler` looks up `init`,
`on_match` and `close` exactly, so a function called `free` would never run during cleanup.

Java passes the init struct through `init`'s argument, and `init` returns the context. Keep the
two structs on the Java side in step with the C++ side: mirror each one as a `MemoryLayout`, and
add `static_assert(offsetof(...))` checks in the `.cpp` file so a change to a field fails the build
rather than silently reading the wrong bytes. See
[Passing setup data in](#passing-setup-data-in) and
[Reading results back](#reading-results-back).

`extern "C"` keeps the symbol names unmangled, so that `NativeMatchHandler` can find
them by name. C++ member functions can never be given C linkage, which is why the entry points are
free functions rather than methods.

Each `NativeMatchHandler` calls `init` once and gets its own context, so two handlers built from the
same library do not share state. They share code, not data.

For more working handlers, see the sources in
[`src/test/resources/native`](../src/test/resources/native):
[`native_count_match.cpp`](../src/test/resources/native/native_count_match.cpp) counts matches,
[`native_print_match.cpp`](../src/test/resources/native/native_print_match.cpp) prints each one, and
[`native_collect_match.cpp`](../src/test/resources/native/native_collect_match.cpp) stores every match in a buffer that grows as needed.
[`native_collect_match.cpp`](../src/main/resources/native/buffered_match_handler.cpp) in combination with the 
[`BufferedMatchHandler`](../src/main/java/com/dynatrace/vectorscan4j/BufferedMatchHandler.java) is a default implementation 
already included in vectorscan4j. You can use this implementation for buffered match handling on the Java-side. 

`extern "C"` matters: it is what keeps the symbol names unmangled, so that `NativeMatchHandler` can
find them by name. C++ member functions can never be given C linkage, which is why the entry points
are free functions rather than methods.

Each `NativeMatchHandler` calls `init` once and gets its own context, so two handlers built from the
same library do not share state. They share code, not data.

## Building your handler

A handler library is completely standalone. It does **not** link against vectorscan4j or against
vectorscan — it only exports symbols that get resolved at runtime. There is nothing to link.

```bash
# Linux
c++ -fPIC -shared my_handler.cpp -o libmyhandler.so

# macOS
c++ -dynamiclib my_handler.cpp -o libmyhandler.dylib
```

You can check the result exposes what you expect:

```bash
$ nm -D --defined-only libmyhandler.so | grep ' T '
00000000000011a1 T close
0000000000001139 T init
000000000000115a T on_match
```

## Using it from Java

```java
Path lib = Path.of("/path/to/libmyhandler.so");

try (Database db = new Database(List.of(new Expression("the")), BLOCK_MODE);
        BlockScanner scanner = new BlockScanner(db);
        NativeMatchHandler handler = new NativeMatchHandler(lib)) {

    scanner.scan("the cat and the dog", handler);
}
```

The constructor takes only the library path. It resolves `init`, `on_match` and `close` from that
library, so there is no name to pass.

### Passing setup data in

The two-argument constructor hands a pointer to `init`. Pack whatever the handler needs into a
struct and read it there:

```cpp
#include <cstdint>
#include <cstdlib>

struct CounterConfig {
    int32_t maxId;
};

struct CounterContext {
    uint64_t matches;
    uint64_t lastEnd;
    int32_t maxId;
};

extern "C" {

void *init(void *arg) {
    CounterContext *ctx = static_cast<CounterContext *>(calloc(1, sizeof(CounterContext)));
    ctx->maxId = arg != nullptr ? static_cast<const CounterConfig *>(arg)->maxId : -1;
    return ctx;
}

} // extern "C"
```

```java
MemorySegment config = arena.allocate(ValueLayout.JAVA_INT);
config.set(ValueLayout.JAVA_INT, 0L, 5);

try (NativeMatchHandler handler = new NativeMatchHandler(lib, config)) {
    scanner.scan(input, handler);
}
```

`init` receives a single `void *` rather than real parameters, because Java builds the
`FunctionDescriptor` for `init` itself and cannot discover a signature, so the arity is fixed by the
contract. The one-argument constructor passes `NULL`, so check for it if the argument is optional.

`init` is the only place the pointer is used. The segment only has to outlive that call — unless the
handler keeps the pointer rather than copying what it points at, in which case it has to outlive the
handler.

## Reading results back

Because the context is a plain struct, Java can read it directly through `handler.context()`.
Mirror the struct once as a `MemoryLayout` and read the fields through `VarHandle`s:

```java
private static final MemoryLayout COUNTER_STATE = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("matches"),
        ValueLayout.JAVA_LONG.withName("lastEnd"));

private static final VarHandle MATCHES =
        COUNTER_STATE.varHandle(MemoryLayout.PathElement.groupElement("matches"));

long matches = (long) MATCHES.get(handler.context(), 0L);
```

That is a plain memory read with no downcall, and the field names are checked against the layout
rather than being raw offsets.

The layout is duplicated between the two languages, so keep the `static_assert`s on the native side:
they turn a change there into a build failure rather than letting it silently desync from the Java
layout. Nothing checks the Java side, so make sure each field's width matches as well — a
`uint64_t` field is `JAVA_LONG`, not `JAVA_INT`. A mismatch there does not fail; it reads the wrong
bytes.

Read results before closing the handler. Reading `handler.context()` after `close()` throws
`IllegalStateException`, because the context is tied to the handler's lifetime.

## Lifetime and ownership

`NativeMatchHandler` owns two things: the native object returned by `init`, and the loaded library.

- `close()` calls the library's `close` on the context and then unloads the library, in that order.
- A `Cleaner` runs the same cleanup if the handler becomes unreachable without being closed, so a
  forgotten handler is still cleaned up. It is still best to close it deterministically.
- `close()` is idempotent; calling it twice will not free the native object twice.
- Scanning with a closed handler throws `IllegalStateException: Already closed`, and so does reading
  its context.

A single handler wraps a single native object, so it is **not** safe to use concurrently from
multiple scanners on multiple threads. Create one handler per thread.

## Constraints and pitfalls

**Use `extern "C"` and the exact names `init`, `on_match` and `close`.** A function in a C++
namespace, or one without `extern "C"`, gets a mangled name and will not be found. One handler per
shared library, as described above.

**Do not let C++ exceptions escape.** The entry points are called across boundaries that cannot
unwind a C++ exception — `on_match` from vectorscan's C frames, `init` and `close` from the JVM
through a downcall — and an escaping exception is undefined behaviour. Mark the functions `noexcept`
to turn that into a deterministic `std::terminate`, or catch inside `on_match` and return non-zero to
stop the scan instead.

**Size buffers in the units you index them in.** If each match takes three `int32_t` slots, a buffer
for `n` matches needs `3 * n * sizeof(int32_t)` bytes. An undersized buffer overflows into the heap,
and the crash usually surfaces later inside `malloc` or `free` rather than where the bug is.

**Match offsets follow vectorscan's rules.** `from` is only meaningful if the pattern was compiled
with `SOM_LEFTMOST`; otherwise it is reported as `0`. Offsets are byte offsets into the scanned
input, which for text scanned as UTF-8 are not character offsets.

**The signature is trusted.** The JVM cannot verify a function's true ABI from its address. If
`on_match` does not match `match_event_handler` exactly — including its `int` return type — the
result is undefined behaviour at scan time, not an exception. A cheap compile-time check is to
assign your function to a pointer of the expected type:

```cpp
static int (*const check)(unsigned int, unsigned long long, unsigned long long, unsigned int, void *)
        = on_match;    // a mismatched signature is a compile error in C++
```

**Handler libraries are platform-specific.** You need to build and ship one `.so` / `.dylib` per
target platform and architecture, just as vectorscan4j does for its own bundled natives.

## Other languages

Nothing in the contract is C++-specific — it is the C ABI. Any language that can export C symbols
from a shared library can provide a handler: C, Rust (`#[no_mangle] pub extern "C" fn`), Zig
(`export fn`), and so on.

The simplest possible handler is a single function, with no `init` and no `close`, running with a
`NULL` context. It is also the only handler you can put in a library by itself without the
one-handler-per-library rule being an issue:

```c
int on_match(unsigned int id,
             unsigned long long from,
             unsigned long long to,
             unsigned int flags,
             void *context) {
    return 0;
}
```

## See also

- [`NativeMatchHandler`](../src/main/java/com/dynatrace/vectorscan4j/NativeMatchHandler.java) — the Java API
- [`NativeMatchHandlerTests`](../src/test/java/com/dynatrace/vectorscan4j/NativeMatchHandlerTests.java) — worked examples
- [`native_count_match.cpp`](../src/test/resources/native/native_count_match.cpp) — a counting handler
- [`native_print_match.cpp`](../src/test/resources/native/native_print_match.cpp) — a handler that prints each match
- [`native_collect_match.cpp`](../src/test/resources/native/native_collect_match.cpp) — a handler that stores matches in a growing buffer
- [`buffered_match_handler.cpp`](../src/main/resources/native/buffered_match_handler.cpp) — the native side of `BufferedMatchHandler`
