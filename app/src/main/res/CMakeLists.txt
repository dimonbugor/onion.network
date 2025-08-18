cmake_minimum_required(VERSION 3.18.1)
project(onion.network)

# ---- Android log ----
find_library(log-lib log)

# ---- ABI-specific lib path ----
set(LIB_DIR ${CMAKE_SOURCE_DIR}/src/main/jniLibs/${CMAKE_ANDROID_ARCH_ABI})

# ---- Prebuilt libraries ----
add_library(tor SHARED IMPORTED)
set_target_properties(tor PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libtor.so)

add_library(obfs4proxy SHARED IMPORTED)
set_target_properties(obfs4proxy PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libobfs4proxy.so)

add_library(snowflake SHARED IMPORTED)
set_target_properties(snowflake PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libsnowflake.so)

add_library(dnscrypt SHARED IMPORTED)
set_target_properties(dnscrypt PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libdnscrypt-proxy.so)

# ---- Ваш нативний модуль ----
add_library(tor-native SHARED
        src/main/cpp/tor_wrapper.cpp
        src/main/cpp/tor_control.cpp
)

target_include_directories(tor-native PRIVATE
        ${LIB_DIR}/include
)

target_link_libraries(tor-native
        ${log-lib}
        tor
        obfs4proxy
        snowflake
        dnscrypt
)

# ---- Оптимізації ----
if(${CMAKE_ANDROID_ARCH_ABI} STREQUAL "arm64-v8a")
    target_compile_options(tor-native PRIVATE -Ofast -march=armv8-a+simd)
elseif(${CMAKE_ANDROID_ARCH_ABI} STREQUAL "armeabi-v7a")
    target_compile_options(tor-native PRIVATE -Ofast -march=armv7-a)
endif()