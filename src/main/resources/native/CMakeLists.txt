cmake_minimum_required(VERSION 3.16)
project(vectorscan4j_native CXX)

set(CMAKE_WINDOWS_EXPORT_ALL_SYMBOLS ON)

add_library(vs4j_collect_match SHARED collect_match.cpp)
set_target_properties(vs4j_collect_match PROPERTIES
    CXX_STANDARD 17
    POSITION_INDEPENDENT_CODE ON
)
