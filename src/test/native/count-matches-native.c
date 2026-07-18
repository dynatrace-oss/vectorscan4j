int vs4j_count_matches(unsigned int id,
                          unsigned long long from,
                          unsigned long long to,
                          unsigned int flags,
                          void *context) {
    unsigned long long *count = (unsigned long long *) context;
    (*count)++;
    return 0;
}
