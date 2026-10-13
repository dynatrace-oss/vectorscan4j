#include <iostream>

extern "C" {
    void init() {
        std::cout << "[native] native_print_handler.init()" << std::endl;
    }

    void on_match(unsigned int id,
                unsigned long long from,
                unsigned long long to,
                unsigned int flags,
                void *context) {
        printf("[native] native_print_handler: Found Match(id=%d, from=%lld, to=%lld)\n", id, from, to);
    }

    void close(void *ctx) {
        std::cout << "[native] native_print_handler.free()" << std::endl;
    }
} // extern "C"

