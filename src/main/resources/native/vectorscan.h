// ------------------------- __stddef_size_t.h --------------------------

#if !defined(_SIZE_T) ||                                                       \
    (__has_feature(modules) && !__building_module(_Builtin_stddef))
#define _SIZE_T

typedef __SIZE_TYPE__ size_t;

#endif

// ------------------------- hs_runtime.h -------------------------
#define ALIGN_ATTR(x) __attribute__((aligned((x))))
typedef unsigned int u32;
typedef unsigned long long ALIGN_ATTR(8) u64a;

// ------------------------- database.h --------------------------
struct hs_database {
    u32 magic;
    u32 version;
    u32 length;
    u64a platform;
    u32 crc32;
    u32 reserved0;
    u32 reserved1;
    u32 bytecode;    // offset relative to db start
    u32 padding[16];
    char bytes[];
};

// ------------------------- hs_common.h --------------------------
#define HS_CDECL

struct hs_database;
typedef struct hs_database hs_database_t;

typedef int hs_error_t;

hs_error_t HS_CDECL hs_serialize_database(
    const hs_database_t *db,
    char **bytes,
    size_t *length
);

hs_error_t HS_CDECL hs_deserialize_database(
    const char *bytes,
    const size_t length,
    hs_database_t **db
);

hs_error_t HS_CDECL hs_database_size(const hs_database_t *database, size_t *database_size);

hs_error_t HS_CDECL hs_database_info(const hs_database_t *database, char **info);

// ------------------------- hs_compile.h -------------------------
typedef struct hs_compile_error {
    char *message;
    int expression;
} hs_compile_error_t;

typedef struct hs_platform_info {
    unsigned int tune;
    unsigned long long cpu_features;
    unsigned long long reserved1;
    unsigned long long reserved2;
} hs_platform_info_t;

hs_error_t HS_CDECL hs_compile_multi(
    const char *const *expressions,
    const unsigned int *flags,
    const unsigned int *ids,
    unsigned int elements, unsigned int mode,
    const hs_platform_info_t *platform,
    hs_database_t **db,
    hs_compile_error_t **error
);

// ------------------------- hs_runtime.h -------------------------

struct hs_stream;

typedef struct hs_stream hs_stream_t;

struct hs_scratch;

typedef struct hs_scratch hs_scratch_t;

typedef int (HS_CDECL *match_event_handler)(
    unsigned int id,
    unsigned long long from,
    unsigned long long to,
    unsigned int flags,
    void *context
);

// ---------- Streaming mode operations ----------
hs_error_t HS_CDECL hs_open_stream(
    const hs_database_t *db,
    unsigned int flags,
    hs_stream_t **stream
);

hs_error_t HS_CDECL hs_scan_stream(
    hs_stream_t *id,
    const char *data,
    unsigned int length,
    unsigned int flags,
    hs_scratch_t *scratch,
    match_event_handler onEvent,
    void *ctxt
);

hs_error_t HS_CDECL hs_close_stream(
    hs_stream_t *id,
    hs_scratch_t *scratch,
    match_event_handler onEvent,
    void *ctxt
);

hs_error_t HS_CDECL hs_reset_stream(
    hs_stream_t *id,
    unsigned int flags,
    hs_scratch_t *scratch,
    match_event_handler onEvent,
    void *context
);

// ---------- Block mode operations ----------
hs_error_t HS_CDECL hs_scan(
    const hs_database_t *db,
    const char *data,
    unsigned int length,
    unsigned int flags,
    hs_scratch_t *scratch,
    match_event_handler onEvent,
    void *context
);

// ---------- Vector mode operations ----------
hs_error_t HS_CDECL hs_scan_vector(
    const hs_database_t *db,
    const char *const *data,
    const unsigned int *length,
    unsigned int count, unsigned int flags,
    hs_scratch_t *scratch,
    match_event_handler onEvent, void *context
);

hs_error_t HS_CDECL hs_alloc_scratch(const hs_database_t *db, hs_scratch_t **scratch);
