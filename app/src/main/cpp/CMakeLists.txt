cmake_minimum_required(VERSION 3.22.1)
project(puhe_lame)

# LAME 3.100 encoder sources (libmp3lame, decoder interface excluded).
# NOTE: lame/vector/*.c are x86-only SIMD helpers, never compiled;
# only lame_intrin.h is included (with portable fallbacks on ARM).
file(GLOB LAME_SRC lame/*.c)

add_library(puhe_lame SHARED lame_bridge.c ${LAME_SRC})

# config.h (this dir) + lame/ headers
target_include_directories(puhe_lame PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} lame)
target_compile_definitions(puhe_lame PRIVATE HAVE_CONFIG_H STDC_HEADERS NDEBUG)
target_compile_options(puhe_lame PRIVATE -O3)

find_library(log-lib log)
target_link_libraries(puhe_lame ${log-lib})
