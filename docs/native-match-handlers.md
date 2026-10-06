# Native match handlers

A `NativeMatchHandler` lets vectorscan call your own native function on every match, instead of
calling back up into the JVM. Every match normally costs one native-to-Java upcall; on workloads
that produce a very large number of matches, that overhead can dominate the scan itself. Handling
matches natively removes it entirely.

This is an advanced feature. If your handler needs to do anything substantial with each match in
Java, the upcall is not your bottleneck and a plain
[`MatchHandler`](../src/main/java/com/dynatrace/vectorscan4j/MatchHandler.java) is the right choice.
If you want to reduce upcall overhead without leaving Java, consider
[`BatchedMatchHandler`](../src/main/java/com/dynatrace/vectorscan4j/BatchedMatchHandler.java) first —
it collects matches natively and delivers them a batch at a time, so you pay one upcall per batch
rather than one per match.

## Contents

- [The ABI contract](#the-abi-contract)
- [Writing a handler](#writing-a-handler)
- [Building your handler](#building-your-handler)
- [Using it from Java](#using-it-from-java)
- [Lifetime and ownership](#lifetime-and-ownership)
- [Constraints and pitfalls](#constraints-and-pitfalls)
- [Other languages](#other-languages)

## The ABI contract

`NativeMatchHandler` is given a shared library and a name, and resolves up to three `extern "C"`
symbols from it. For the name `Counter`:

| symbol | signature | required |
|---|---|---|
| `Counter_init` | `void *(void *)` | no |
| `Counter_on_match` | `int (unsigned int, unsigned long long, unsigned long long, unsigned int, void *)` | **yes** |
| `Counter_free` | `void (void *)` | no |
| `Counter_state` | `void *(void *)` | no |

Semantics:

- `_init` is called once when the `NativeMatchHandler` is constructed. Whatever pointer it returns
  becomes the `context` argument passed to every `_on_match` call. If the symbol is absent, the
  context is `NULL`. Its own argument is whatever pointer was passed to the three-argument
  `NativeMatchHandler` constructor, or `NULL` — see [Passing setup data in](#passing-setup-data-in).
- `_on_match` is called once per match, with the pattern id, the start and end offsets, the match
  flags, and the context. Return `0` to continue scanning, non-zero to stop early — vectorscan then
  returns `HS_SCAN_TERMINATED`, which the Java side treats as a normal completion.
- `_free` is called with the same context when the handler is closed or garbage collected. If the
  symbol is absent, nothing is released.
- `_state` is not called by `NativeMatchHandler` itself. It is a convention for handlers whose
  results do not live at the start of the context — see
  [Reading results back](#reading-results-back).

The `_on_match` signature is vectorscan's own `match_event_handler`; vectorscan4j passes the symbol
address straight to `hs_scan`, so the signature must match exactly.

This contract is plain C, so a handler can be written in C, C++, or anything else that exports
C symbols — see [Other languages](#other-languages).

## Writing a handler

A handler is a set of `extern "C"` functions that share one context struct. `_init` allocates the
struct, `_on_match` updates it, and `_free` releases it:

```cpp
#include <cstddef>
#include <cstdint>
#include <cstdlib>

struct CounterState {
    uint64_t matches;
    uint64_t lastEnd;
};

static_assert(offsetof(CounterState, matches) == 0, "CounterState::matches offset changed");
static_assert(offsetof(CounterState, lastEnd) == 8, "CounterState::lastEnd offset changed");

extern "C" {

void *Counter_init(void *arg) {
    return calloc(1, sizeof(CounterState));
}

int Counter_on_match(unsigned int id, unsigned long long from,
                     unsigned long long to, unsigned int flags, void *context) {
    CounterState *state = static_cast<CounterState *>(context);
    ++state->matches;
    state->lastEnd = to;
    return 0;                 // non-zero would stop the scan
}

void Counter_free(void *context) {
    free(context);
}

} // extern "C"
```

`extern "C"` matters: it is what keeps the symbol names unmangled, so that `NativeMatchHandler` can
find them by name. C++ member functions can never be given C linkage, which is why the entry points
are free functions rather than methods.

Each `NativeMatchHandler` calls `_init` once and gets its own context, so two handlers built from the
same library and the same name do not share state. They share code, not data.

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
0000000000001254 T Counter_free
00000000000011d9 T Counter_init
000000000000120b T Counter_on_match
```

## Using it from Java

```java
Path lib = Path.of("/path/to/libmyhandler.so");

try (Database db = new Database(List.of(new Expression("the")), BLOCK_MODE);
        BlockScanner scanner = new BlockScanner(db);
        NativeMatchHandler handler = new NativeMatchHandler(lib, "Counter")) {

    scanner.scan("the cat and the dog", handler);
}
```

The second argument is the symbol prefix: `"Counter"` resolves `Counter_init`, `Counter_on_match`
and `Counter_free`.

### Passing setup data in

The three-argument constructor hands a pointer to `_init`. Pack whatever the handler needs into a
struct and read it there:

```cpp
struct CounterConfig {
    int32_t maxId;
};

void *Counter_init(void *arg) {
    CounterContext *ctx = static_cast<CounterContext *>(calloc(1, sizeof(CounterContext)));
    ctx->maxId = arg != nullptr ? static_cast<const CounterConfig *>(arg)->maxId : -1;
    return ctx;
}
```

```java
MemorySegment config = arena.allocate(ValueLayout.JAVA_INT);
config.set(ValueLayout.JAVA_INT, 0L, 5);

try (NativeMatchHandler handler = new NativeMatchHandler(lib, "Counter", config)) {
    scanner.scan(input, handler);
}
```

It is a single `void *` rather than real parameters because Java builds the `FunctionDescriptor` for
`_init` itself and cannot discover a signature, so the arity is fixed by the contract. The
two-argument constructor passes `NULL`, so check for it if the argument is optional.

`_init` is the only place the pointer is used. The segment only has to outlive that call — unless
the handler keeps the pointer rather than copying what it points at, in which case it has to outlive
the handler.

### Reading results back

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

If the results are not at the start of the context — say they sit inside a larger struct — export a
`<Name>_state(void *context)` that returns a pointer to them, and read through that instead.

Read results before closing the handler. `close()` frees the context, so reading it afterwards is a
use-after-free, and it is not detected: the read returns whatever is left in freed memory. (Scanning
with a closed handler *is* detected — see below.)

## Lifetime and ownership

`NativeMatchHandler` owns two things: the native object returned by `_init`, and the loaded library.

- `close()` calls `_free` on the context and then unloads the library, in that order.
- A `Cleaner` runs the same cleanup if the handler becomes unreachable without being closed, so a
  forgotten handler leaks nothing permanently. It is still best to close it deterministically.
- `close()` is idempotent; calling it twice will not free the native object twice.
- Using a handler after closing it throws `IllegalStateException: Already closed` rather than
  crashing — closing the handler invalidates the scope of the function pointer, and the FFM downcall
  rejects it before any native code runs.

A single handler wraps a single native object, so it is **not** safe to use concurrently from
multiple scanners on multiple threads. Create one handler per thread.

## Constraints and pitfalls

**Use `extern "C"` and a single, unqualified prefix.** The prefix is concatenated with `_init`,
`_on_match` and so on, so the symbols must be exported under exactly those names. A function in a
C++ namespace, or one without `extern "C"`, gets a mangled name and will not be found.

**Do not let C++ exceptions escape.** The entry points are called across boundaries that cannot
unwind a C++ exception — `_on_match` from vectorscan's C frames, `_init` and `_free` from the JVM
through a downcall — and an escaping exception is undefined behaviour. Mark the functions
`noexcept` to turn that into a deterministic `std::terminate`, or catch inside `_on_match` and return
non-zero to stop the scan instead.

**Size buffers in the units you index them in.** If each match takes three `int32_t` slots, a buffer
for `n` matches needs `3 * n * sizeof(int32_t)` bytes. An undersized buffer overflows into the heap,
and the crash usually surfaces later inside `malloc` or `free` rather than where the bug is.

**Match offsets follow vectorscan's rules.** `from` is only meaningful if the pattern was compiled
with `SOM_LEFTMOST`; otherwise it is reported as `0`. Offsets are byte offsets into the scanned
input, which for text scanned as UTF-8 are not character offsets.

**The signature is trusted.** The JVM cannot verify a function's true ABI from its address. If
`_on_match` does not match `match_event_handler` exactly — including its `int` return type — the
result is undefined behaviour at scan time, not an exception. A cheap compile-time check is to
assign your function to a pointer of the expected type:

```cpp
static int (*const check)(unsigned int, unsigned long long, unsigned long long, unsigned int, void *)
        = Counter_on_match;    // a mismatched signature is a compile error in C++
```

**Handler libraries are platform-specific.** You need to build and ship one `.so` / `.dylib` per
target platform and architecture, just as vectorscan4j does for its own bundled natives.

## Other languages

Nothing in the contract is C++-specific — it is the C ABI. Any language that can export C symbols
from a shared library can provide a handler: C, Rust (`#[no_mangle] pub extern "C" fn`), Zig
(`export fn`), and so on.

The simplest possible handler is a single function, with no `_init` and no `_free`, running with a
`NULL` context:

```c
int Stateless_on_match(unsigned int id,
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
- [`count-matches-native.cpp`](../src/test/resources/native/count-matches-native.cpp) — the test handlers, including a stateless one
