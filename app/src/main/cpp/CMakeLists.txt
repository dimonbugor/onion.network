cmake_minimum_required(VERSION 3.22.1)
project(tor-native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# STL
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++17 -fexceptions -frtti")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -lc++_shared")

# wrapper
add_library(tor-native SHARED
        ${CMAKE_CURRENT_SOURCE_DIR}/tor-native.cpp
#        ${CMAKE_CURRENT_SOURCE_DIR}/invizible/invizible.c
#        ${CMAKE_CURRENT_SOURCE_DIR}/invizible/session.c
#        ${CMAKE_CURRENT_SOURCE_DIR}/invizible/ip.c
#        ${CMAKE_CURRENT_SOURCE_DIR}/invizible/tcp.c
#        ${CMAKE_CURRENT_SOURCE_DIR}/invizible/udp.c
#        ${CMAKE_CURRENT_SOURCE_DIR}/invizible/icmp.c
#        ${CMAKE_CURRENT_SOURCE_DIR}/invizible/dns.c
#        ${CMAKE_CURRENT_SOURCE_DIR}/invizible/dhcp.c
#        ${CMAKE_CURRENT_SOURCE_DIR}/invizible/util.c
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

if(${CMAKE_ANDROID_ARCH_ABI} STREQUAL "arm64-v8a")
    target_compile_options(tor-native PRIVATE -Ofast -march=armv8-a+simd -flto=thin -funsafe-math-optimizations -ffast-math -ftree-vectorize -fvectorize -fslp-vectorize)
    target_link_options(tor-native PRIVATE -Wl,-z,max-page-size=16384)
elseif(${CMAKE_ANDROID_ARCH_ABI} STREQUAL "armeabi-v7a")
    target_compile_options(tor-native PRIVATE -Ofast -march=armv7-a -flto=thin -funsafe-math-optimizations -ffast-math -ftree-vectorize -fvectorize -fslp-vectorize)
else ()
    target_compile_options(tor-native PRIVATE -Ofast -march=x86-64 -flto=thin -funsafe-math-optimizations -ffast-math -ftree-vectorize -fvectorize -fslp-vectorize)
    target_link_options(tor-native PRIVATE -Wl,-z,max-page-size=16384)
endif()