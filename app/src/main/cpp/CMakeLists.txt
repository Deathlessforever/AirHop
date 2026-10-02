cmake_minimum_required(VERSION 3.22.1)

project("airhop_core" LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_compile_options(-Wall -Wextra -O3 -fPIC)

add_library(
    airhop_core
    SHARED
    native_bridge.cpp
    gf256.cpp
    reed_solomon.cpp
    fnv1a.cpp
    audio_ring_buffer.cpp
    silero_vad.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    airhop_core
    ${log-lib}
    ${android-lib}
)
