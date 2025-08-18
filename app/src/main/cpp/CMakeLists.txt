cmake_minimum_required(VERSION 3.22.1)
project(tor-native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# wrapper
add_library(tor-native SHARED
        ${CMAKE_CURRENT_SOURCE_DIR}/tor-native.cpp
)

target_include_directories(tor-native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# Android log
find_library(log-lib log)

# NDK C++ runtime
find_library(cpp_shared-lib c++_shared)

# лінкуємо
target_link_libraries(tor-native
        ${log-lib}
        ${cpp_shared-lib}
)