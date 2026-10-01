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
- [Writing a handler in C++](#writing-a-handler-in-c)
- [Getting the header](#getting-the-header)
- [Building your handler](#building-your-handler)
- [Using it from Java](#using-it-from-java)
- [Lifetime and ownership](#lifetime-and-ownership)
- [Constraints and pitfalls](#constraints-and-pitfalls)
- [Writing a handler in another language](#writing-a-handler-in-another-language)

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
- `_state` optionally returns a pointer to a standard-layout struct holding the handler's results,
  so Java can read fields directly rather than calling back in for each one. Returning null, or
  omitting the symbol, means the handler exposes no state. See
  [Reading a whole state struct](#reading-a-whole-state-struct).

The `_on_match` signature is vectorscan's own `match_event_handler`; vectorscan4j passes the symbol
address straight to `hs_scan`, so the signature must match exactly.

This contract is plain C. Nothing about it requires C++ — see
[Writing a handler in another language](#writing-a-handler-in-another-language).

## Writing a handler in C++

The bundled `vs4j_handler.h` provides a base class and a macro that generate the three entry points
for you. You write a class; the macro writes the shim.

```cpp
#include "vs4j_handler.h"

class Counter : public vs4j::MatchHandler {
public:
    int on_match(unsigned int id, unsigned long long from,
                 unsigned long long to, unsigned int flags) override {
        ++count_;
        return 0;                 // non-zero would stop the scan
    }

private:
    unsigned long long count_ = 0;
};

VS4J_REGISTER_HANDLER(Counter)
```

That is the whole handler. `VS4J_REGISTER_HANDLER` emits `Counter_init`, `Counter_on_match`,
`Counter_free` and `Counter_state`, using the class name itself as the symbol prefix.

The shim has to be generated rather than hand-written because a C++ member function can never be
given C linkage — member names are always mangled, and the mangling is compiler- and ABI-specific.
There is no portable way to point `dlsym` at `Counter::on_match`.

Deriving from `vs4j::MatchHandler` is what buys you compile-time safety. The macro rejects, at
build time:

| mistake | error |
|---|---|
| `on_match` has the wrong signature | `marked 'override', but does not override` |
| class does not derive from `vs4j::MatchHandler` | `static assertion failed: R must derive from vs4j::MatchHandler` |
| `on_match` not implemented | `static assertion failed: A must implement on_match; it is still abstract` |
| no default constructor | `static assertion failed: N must be default-constructible` |
| name is not a defined class | `'NoSuchClass' was not declared in this scope` |

Without the base class, a signature mismatch would compile cleanly and become undefined behaviour
at scan time.

### State is per handler, not per class

The macro does not turn your class into anything static. `Counter` stays an ordinary C++ class, and
the generated functions are stateless — the instance lives behind the `context` pointer:

```
Counter_init(arg)         ->  new Counter(), then configure(arg)         a fresh heap object
Counter_on_match(…, ctx)  ->  static_cast<Counter*>(ctx)->on_match(…)    called on that object
Counter_free(ctx)         ->  delete static_cast<Counter*>(ctx)
```

So two `NativeMatchHandler` instances built from the same library and the same name each call
`Counter_init()` and get their own `Counter` object with its own members. They share code, not data.

## Getting the header

`vs4j_handler.h` ships inside the vectorscan4j JAR at `native/vs4j_handler.h`. A C++ compiler
cannot read a JAR, so extract it into your include path first:

```bash
unzip -j -q vectorscan4j-<version>.jar 'native/vs4j_handler.h' -d include/
```

With Gradle, a `Copy` task over `zipTree` of the resolved dependency does the same thing as part of
your build. Resolving the JAR through its own configuration keeps it off your compile classpath:

```groovy
configurations {
    vs4jHeader
}

dependencies {
    vs4jHeader "com.dynatrace.vectorscan4j:vectorscan4j:<version>"
}

tasks.register('extractVs4jHeader', Copy) {
    from(zipTree(configurations.vs4jHeader.singleFile)) {
        include 'native/vs4j_handler.h'
    }
    into layout.buildDirectory.dir('vs4j-include')
    eachFile { it.relativePath = new RelativePath(true, it.name) }   // drop the native/ prefix
    includeEmptyDirs = false
}
```

The same thing in the Kotlin DSL:

```kotlin
val vs4jHeader by configurations.creating

dependencies {
    vs4jHeader("com.dynatrace.vectorscan4j:vectorscan4j:<version>")
}

tasks.register<Copy>("extractVs4jHeader") {
    from(zipTree(vs4jHeader.singleFile)) {
        include("native/vs4j_handler.h")
    }
    into(layout.buildDirectory.dir("vs4j-include"))
    eachFile { relativePath = RelativePath(true, name) }   // drop the native/ prefix
    includeEmptyDirs = false
}
```

Either way the header lands at `build/vs4j-include/vs4j_handler.h`, ready to pass to your compiler as
`-I build/vs4j-include`. Wire whatever task compiles your handler to `dependsOn("extractVs4jHeader")`
so the header is always refreshed from the JAR you currently depend on.

Prefer extracting the header from the JAR you actually depend on over vendoring a copy, so the
header and the Java side cannot drift apart across upgrades.

## Building your handler

A handler library is completely standalone. It does **not** link against vectorscan4j or against
vectorscan — it only exports symbols that get resolved at runtime. There is nothing to link.

```bash
# Linux
c++ -std=c++11 -I include -fPIC -shared my_handler.cpp -o libmyhandler.so

# macOS
c++ -std=c++11 -I include -dynamiclib my_handler.cpp -o libmyhandler.dylib
```

`vs4j_handler.h` requires only **C++11**. Compile with a newer standard if you like; the header does
not need one.

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

The second argument is the symbol prefix, which for a macro-registered handler is the class name.

To read results back, give your handler a state struct and override `state()`. The next section
covers that in full; it is the only supported way to get data out, because decoding
`handler.context()` directly is not safe. A class deriving from `vs4j::MatchHandler` is polymorphic,
so offset 0 holds the vtable pointer rather than your first member, and unlike a plain C struct
there is no portable `offsetof` guard you could pin that layout with — a wrong offset would read the
vptr and return a plausible-looking wrong number.

If you need something a single struct cannot express — a computed value, or several unrelated
results — write the `extern "C"` shim by hand alongside your class:

```cpp
extern "C" unsigned long long Timer_elapsedNanos(void *context) noexcept {
    return static_cast<Timer *>(context)->elapsedNanos();
}
```

Mark it `noexcept` for the same reason the generated entry points are: it is called from the JVM
through a downcall, which cannot unwind a C++ exception.

### Passing setup data in

`configure()` is the mirror of `state()`: Java hands a pointer in at construction, the handler reads
what it needs, and the default ignores it. Override it when your handler needs setup data:

```cpp
struct CounterConfig {
    int32_t maxId;
};

class Counter : public vs4j::MatchHandler {
public:
    void configure(void *arg) override {
        if (arg) {
            maxId_ = static_cast<const CounterConfig *>(arg)->maxId;
        }
    }
    // ...
private:
    int32_t maxId_ = -1;
};
```

```java
MemorySegment config = arena.allocate(ValueLayout.JAVA_INT);
config.set(ValueLayout.JAVA_INT, 0L, 5);

try (NativeMatchHandler handler = new NativeMatchHandler(lib, "Counter", config)) {
    scanner.scan(input, handler);
}
```

A single `void *` rather than real parameters, because Java builds the `FunctionDescriptor` for
`_init` itself and cannot discover a signature — so the arity is fixed by the contract. Pack several
values into a standard-layout struct and pass a pointer to it, exactly as `state()` hands one back.

`configure()` runs once, inside `_init`, immediately after the handler is constructed. The segment
only has to outlive that call; if the handler keeps the pointer rather than copying what it points
at, the segment has to outlive the handler. The two-argument constructor passes `NULL`, so a handler
that never overrides `configure()` needs no change.

### Reading a whole state struct

An accessor costs a downcall per call. When a handler has several fields, or Java reads them often,
it is cheaper to keep the handler's state in a **standard-layout struct**, export a pointer to it
once, and read the fields with a `MemoryLayout`.

This works only if the struct is standard-layout. A class deriving from `vs4j::MatchHandler` is
polymorphic, so it is *not* standard-layout — `offsetof` on it is not portable, and offset 0 holds
the vtable pointer rather than your first member. Putting the data in its own struct sidesteps both
problems and lets you pin the layout at compile time:

```cpp
struct CounterState {
    uint64_t matches;
    uint64_t lastEnd;
};

static_assert(sizeof(CounterState) == 16, "CounterState size changed; update the Java layout");
static_assert(offsetof(CounterState, matches) == 0, "CounterState::matches offset changed");
static_assert(offsetof(CounterState, lastEnd) == 8, "CounterState::lastEnd offset changed");

class Counter : public vs4j::MatchHandler {
public:
    int on_match(unsigned int id, unsigned long long from,
                 unsigned long long to, unsigned int flags) override {
        ++state_.matches;
        state_.lastEnd = to;
        return 0;
    }
    void *state() override { return &state_; }

private:
    CounterState state_ = {0, 0};
};

VS4J_REGISTER_HANDLER(Counter)
```

`state()` is a virtual on `vs4j::MatchHandler` that defaults to returning null, so
`VS4J_REGISTER_HANDLER` always emits `<Type>_state` and you only override it when you have
something to expose. Nothing extra to register.

On the Java side, mirror the struct once and read through `VarHandle`s:

```java
private static final MemoryLayout COUNTER_STATE = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("matches"),
        ValueLayout.JAVA_LONG.withName("lastEnd"));

private static final VarHandle MATCHES =
        COUNTER_STATE.varHandle(MemoryLayout.PathElement.groupElement("matches"));

// once, after construction
MemorySegment fn = SymbolLookup.libraryLookup(lib, arena).findOrThrow("Counter_state");
MethodHandle stateFn = Linker.nativeLinker()
        .downcallHandle(fn, FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
MemorySegment state = ((MemorySegment) stateFn.invoke(handler.context()))
        .reinterpret(COUNTER_STATE.byteSize());

// thereafter, no downcalls
long matches = (long) MATCHES.get(state, 0L);
```

You pay one downcall to locate the struct and none per field after that, and the field names are
checked against the layout rather than being raw offsets.

The layout is still duplicated between the two languages, so keep the `static_assert`s: they turn a
change on the C++ side into a build failure there, rather than letting it silently desync from the
Java layout. Nothing can verify the Java side from C++, which is why this pattern suits a struct you
control, not one you are reverse-engineering.

The same shape works without C++ at all. A plain C handler can define its context as this struct
directly, with `_init` allocating it and `_free` releasing it — which is exactly how vectorscan4j's
own bundled `collect_match.cpp` works internally. Writing the entry points as free functions rather
than a class does lose the signature checking the base class provides, though you can recover most
of it by assigning your functions to a struct of typed function pointers: in C++ a mismatch is then
a hard error, and in C a warning that `-Werror` promotes.

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

**The class name must be a single unqualified identifier.** It is pasted into the symbol names, so a
namespace-qualified name does not work — `VS4J_REGISTER_HANDLER(ns::Q)` expands to a definition of
`ns::Q_init` and the compiler rejects it with *"should have been declared inside 'ns'"*. Register such
a class through a global alias:

```cpp
using Q = ns::Q;
VS4J_REGISTER_HANDLER(Q)     // exports Q_init / Q_on_match / Q_free
```

**Do not let exceptions escape.** All three generated entry points are `noexcept`. They are called
across boundaries that cannot unwind a C++ exception — `_on_match` from vectorscan's C frames,
`_init` and `_free` from the JVM through a downcall — where an escaping exception would be undefined
behaviour. `noexcept` turns that into a deterministic `std::terminate`, which aborts the process. If
you would rather stop the scan than abort, catch inside your own `on_match` and return non-zero.

**Match offsets follow vectorscan's rules.** `from` is only meaningful if the pattern was compiled
with `SOM_LEFTMOST`; otherwise it is reported as `0`. Offsets are byte offsets into the scanned
input, which for text scanned as UTF-8 are not character offsets.

**The signature is trusted.** The JVM cannot verify a function's true ABI from its address. If your
`_on_match` does not match `match_event_handler` exactly, the result is undefined behaviour at scan
time, not an exception. Using the C++ header removes this risk; hand-written handlers carry it.

**Handler libraries are platform-specific.** You need to build and ship one `.so` / `.dylib` per
target platform and architecture, just as vectorscan4j does for its own bundled natives.

## Writing a handler in another language

Nothing in the contract is C++-specific — it is the C ABI. Any language that can export C symbols
from a shared library can provide a handler; the C++ header is a convenience, not a requirement.

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

For a stateful handler in C, allocate in `_init` and release in `_free`:

```c
void *Counter_init(void *arg) {            /* arg is unused here */
    unsigned long long *n = calloc(1, sizeof *n);
    return n;
}

int Counter_on_match(unsigned int id, unsigned long long from,
                     unsigned long long to, unsigned int flags, void *context) {
    ++*(unsigned long long *) context;
    return 0;
}

void Counter_free(void *context) { free(context); }
```

The same shape works from Rust (`#[no_mangle] pub extern "C" fn`), Zig (`export fn`), or anything
else with C-ABI export. You lose the compile-time signature checking the C++ base class provides, so
the signatures become your responsibility.

## See also

- [`NativeMatchHandler`](../src/main/java/com/dynatrace/vectorscan4j/NativeMatchHandler.java) — the Java API
- [`vs4j_handler.h`](../src/main/resources/native/vs4j_handler.h) — the C++ support header
- [`NativeMatchHandlerTests`](../src/test/java/com/dynatrace/vectorscan4j/NativeMatchHandlerTests.java) — worked examples
- [`count-matches-native.cpp`](../src/test/resources/native/count-matches-native.cpp) — the test handlers, including a hand-written stateless one
