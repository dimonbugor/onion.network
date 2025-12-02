cmake_minimum_required(VERSION 3.22.1)

set(CMAKE_C_STANDARD 99)
project(libopusJNI C)

set(libopus_root "${CMAKE_CURRENT_SOURCE_DIR}/../opus")
add_subdirectory("${libopus_root}" "${CMAKE_CURRENT_BINARY_DIR}/opus" EXCLUDE_FROM_ALL)

target_link_options(opus PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
