# vectorscan4j

vectorscan4j is a Java wrapper around the high-performance, multi-regex pattern matching engine vectorscan (https://github.com/VectorCamp/vectorscan).
It supports the simultaneous matching of thousands of different string- and/or regex patterns on input data. Internally, it uses a Finite State machine-based execution through a hybrid DFA/NFA automaton. 

## Contents

- [Features](#features)
- [Installation](#installation)
- [General Usage Framework](#usage)
- [Code Example](#code-example)
- [Database Serialization/Deserialization](#database-serializationdeserialization)
- [Incorrect Usage Patterns](#incorrect-usage-patterns)
- [Limitations](#limitations)
- [Third-Party Licenses](#third-party-licenses)
- [Contributing](#contributing)

## Features

* Leverages vectorscan's optimized engine for fast, SIMD-powered multi-regex pattern matching.
* Supports all common regex features. 
* Provides additionally a wide set of different feature flags that can be set for each pattern individually (for example enabling UTF8 support, case-insensitive matching, and more).
* Supports two different modes of execution:
  * Block mode: Scan complete buffers in isolation ("stateless" execution).
  * Streaming mode: Maintain the internal state across multiple scan calls (supports matches spanning chunk boundaries). Useful in a streaming/networking context, where data is split up in chunks.
* It has a flexible callback-based match processing. The user can provide custom logic that is executed everytime a match is found (see [the code example](#code-example)). 
* Uses Java's new [Foreign Function & Memory API](https://docs.oracle.com/en/java/javase/25/core/foreign-function-and-memory-api.html) for safe and efficient interoperability between JVM- and native execution. 

## Installation

### Maven

```xml
<dependency>
  <groupId>com.dynatrace.vectorscan4j</groupId>
  <artifactId>vectorscan4j</artifactId>
  <version>0.1.0</version>
</dependency>
```

### Gradle

```groovy
implementation "com.dynatrace.vectorscan4j:vectorscan4j:0.1.0"
```

### Requirements

- **Java 22+** (uses the [Foreign Function & Memory API](https://docs.oracle.com/en/java/javase/25/core/foreign-function-and-memory-api.html))
- **Linux** (x86-64 or aarch64) — native vectorscan binaries are bundled in the JAR

## Usage

The vectorscan4j usage is split up into multiple steps:
1. **Compile a "database" from a set of literal/regex patterns:** 
This database is an encoding of the hybrid DFA/NFA automaton that vectorscan traverses internally. 
For a fixed set of patterns, this compilation only needs to happen once. 
The wrapper also provides database serialization/deserialization utilities, so it is easy to send that database over a network, or save to and load from a file.  
Nevertheless, if the set of patterns changes, the database has to be recompiled from scratch. 
We recommend measuring both the database compilation time and the final in-memory size of the compiled database for your use-case. 
Generally speaking, the more patterns you compile and the higher their size and complexity, the higher the compilation time and memory requirements.    
The database itself is constant and is never modified during the actual scanning, which makes it safe to use in multi-threaded applications. 

2. **Create a scanner object from the compiled database:**
Each scanner object allocates and maintains its own scratch space, which keeps track of its internal state required for 
the native vectorscan call. The native functions themselves are therefore completely allocation-free. 
The scratch space, and by extension the scanner object, are modified during the scanning and are thus **not** safe to use from multiple threads.

3. **Call the scanner object's scan() methods for fast, SIMD-powered scanning:**
A scanner's scan() method requires two arguments. 
   1. First, an object holding the payload for what you want to scan over. 
The wrapper supports Strings, byte arrays, ByteBuffers (both on-heap and direct), and MemorySegments (both heap and native).
   2. Second, an [OnMatchEventHandler](src/main/java/com/dynatrace/vectorscan4j/OnMatchEventHandler.java), which is a user-provided callback that gets executed on every match. 
Through that callback, the user can pass arbitrary logic to be executed on every match.  

## Code example

As a first example: Given a fixed set of literal patterns and an input String, 
we want to count how often each pattern exists inside the input:

``` java 
    @Test
    void countLiteralPatternOccurrences() {
        List<Expression> exprs = List.of(
                new Expression("foo"),
                new Expression("bar", EnumSet.of(Flags.CASELESS)),
                new Expression("qux")
        );

        String input = "foo bar foo baz Bar foo";
        int[] countsById = new int[exprs.size()];

        try (Database database = new Database(exprs, ExecutionMode.BLOCK);
             BlockScanner scanner = new BlockScanner(database)) {
             OnMatchEventHandler countMatches = (id, from, to, flags) -> {
                countsById[id]++;
                return true;
            }; 
            scanner.scan(input, countMatches);
        }
        for (int i = 0; i < exprs.size(); i++) {
            IO.println(String.format("%s -> matched %d times.", exprs.get(i).pattern(), countsById[i]));
        }
        // foo -> matched 3 times.
        // bar -> matched 2 times.
        // qux -> matched 0 times.
    }
```
The list of Expression objects specify the set of patterns we want to match. Every one of them is assigned an id
starting from 0, in the order in which they are added to the List. A Database object gets instantiated by passing it 
the list of expressions, and specifying an execution mode. A Scanner object then references the Database 
(BlockScanner objects for Databases with Block execution mode, and StreamScanner objects for Databases with Stream execution mode)
For the scan method, you pass it an input, and an OnMatchEventHandler, which is a functional interface that will get executed on every match. It receives 4 arguments:

1. The id of the expression that matched.
2. The byte position of where the match started. Note that by default, vectorscan does not keep track of this value, unless the pattern flag SOM_LEFTMOST is enabled.
3. The byte position of where the match ended.
4. The flags of the matched expression.
The return value of the callback specifies whether you want the scanning to continue.
Returning true will continue the scan, while returning false will stop the scanning early.

In this example, the provided OnMatchEventHandler increments the value inside an integer array, and then lets the scan continue.  

### Database Serialization/Deserialization

vectorscan4j provides utility methods for serializing and deserializing a Database object:

``` java
    @Test
    void serializeDeserializeDb() {
        List<Expression> expressions = List.of(
                new Expression("pat1"),
                new Expression("pat2")
        );

        try (Database database = new Database(expressions, ExecutionMode.BLOCK)) {
            byte[] dbBytes = database.serialize();
            // ... save dbBytes to file, or send over network ...


            // ...after e.g. reading bytes from a file, deserialize to Database. 
            Database roundTripDb = Database.deserialize(dbBytes);
            roundTripDb.close();
        }
    }
```

## Incorrect Usage Patterns

A small number of niche usage patterns can lead to undefined behavior, or break the JVM.

### 1. Not closing Database / Scanner objects

Database- and Scanner objects both hold native memory, i.e. memory that is not managed by the JVM Runtime. Calling close() on those objects will clean the native memory 
that they are holding. This memory will *also* be freed if one doesn't call the close() explicitly. However, in 
that case it will only happen when the Database- or Scanner object gets garbage collected (which might be far in
the future).
``` java
    // This function allocates off-heap memory and does not release it immediately
    void createDb() {
        // We create a new database, and don't call close() on it.
        Database db = new Database(List.of(new Expression("a")), BLOCK_MODE);

        // After this function returns, db goes out of scope, and will be garbage collected.
        // -> Its native memory gets freed, but not immediately.
    }
```
For more optimal memory usage, either manually close Database and/or Scanner objects by calling the close() method,
 or initialize them with a try-with-resources-block:
``` java
    // This function is safe to call
    void createDbSafe() {
        try (Database db = new Database(List.of(new Expression("a")), BLOCK_MODE)) {
            // Even if an exception gets thrown in here, JVM still closes db at the end of the try-block. 
            throw new RuntimeException("Something bad happened");
        }
    }
```

### 2. Incorrect MemorySegment usage

When using the overloaded Scanner.scan(MemorySegment segment, ...) method, 
passing it a MemorySegment that points to a non-allocated region of memory might create a segmentation fault.
``` java
    try (Database db = new Database(List.of(new Expression("p1")), BLOCK_MODE);
         BlockScanner scanner = new BlockScanner(db);
         Arena arena = Arena.ofConfined()) {
        OnMatchEventHandler doNothing = (_, _, _, _) -> true;
        
        MemorySegment tiny = arena.allocate(10);
        MemorySegment larger = tiny.reinterpret(200000L);
        
        // this scan might cause a segmentation fault, breaking the JVM.
        // If it doesn't cause a segmentation fault, it will scan over a random region of memory,
        // causing undefined behavior.
        scanner.scan(larger, doNothing);
    }
```

### 3. Throwing exceptions inside the OnMatchEventHandler upcall

Throwing exceptions inside the OnMatchEventHandler will always break the JVM, i.e. you can not catch
that exception outside the scan call:

``` java
try (try (Database db = new Database(List.of(new Expression("p1")), BLOCK_MODE);
     BlockScanner scanner = new BlockScanner(db)) {
    OnMatchEventHandler throwException = (_, _, _, _) -> {
        throw new RuntimeException("Hi, try and catch me!");
    };
    try {
        scanner.scan("Here is p1!", throwException);
    } catch(RuntimeException e) {
        IO.println("We caught it!");
    }
    IO.println("We can continue execution.");
    // -> "Unrecoverable uncaught exception encountered. The VM will now exit"
}
```

This is due to how the Foreign Function & Memory API handles exceptions thrown during native upcalls back to Java code.

### 4. Using the same Scanner concurrently in multiple threads

A Scanner object maintains an internal scratch space, which is not safe to use from multiple threads. 
Using the same Scanner from multiple threads will throw a VectorscanException.

## Limitations

### 1. Platform Support is currently Linux-Only

Native vectorscan supports multiple operating systems and CPU architectures (see the [official repository](https://github.com/VectorCamp/vectorscan)).  
At the moment, `vectorscan4j` targets Linux only, on x86-64 and ARM-based CPUs (tested on Ubuntu).

### 2. Not all regex features are fully supported
 
Vectorscan provides only partial support for the following regex concepts:
   * Capture groups
   * Backreferences
   * Look-arounds

By default, vectorscan4j rejects patterns that use those advanced regex features.
If you enable the PREFILTER flag for those patterns, compilation can still succeed, but matching becomes approximate: 
scans may return false positives and should be followed by a secondary exact validation step if exact matches are required.

### 3. No support for Vector mode execution

Currently, there is no support for vectorscan's Vector mode execution.

## Third-Party Licenses

This repository bundles prebuilt native binaries for vectorscan under:

- `src/main/resources/native/linux/x86_64/libvectorscan.so`
- `src/main/resources/native/linux/aarch64/libvectorscan.so`

The corresponding third-party notice and license reproduction is provided in:

- `THIRD_PARTY_NOTICES.md`
- `src/main/resources/native/THIRD_PARTY_NOTICES.txt`

## Contributing

This wrapper currently supports a core subset of vectorscan's features, but not everything. 
If additional features are required, do not hesitate to raise issues or submit pull requests directly.

