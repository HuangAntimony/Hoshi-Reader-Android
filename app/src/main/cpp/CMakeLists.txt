cmake_minimum_required(VERSION 3.22.1)

project(hoshi_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(HOSHIDICTS_BRIDGE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/hoshidicts-kotlin-bridge")
set(HOSHIDICTS_ROOT "${HOSHIDICTS_BRIDGE_ROOT}/app/src/main/cpp/hoshidicts")

add_subdirectory(
    "${HOSHIDICTS_ROOT}"
    "${CMAKE_CURRENT_BINARY_DIR}/hoshidicts"
    EXCLUDE_FROM_ALL
)

if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(hoshidicts PRIVATE "$<$<COMPILE_LANGUAGE:CXX>:-Wno-c23-extensions>")
endif()

add_library(hoshidicts_jni SHARED
    "${HOSHIDICTS_BRIDGE_ROOT}/app/src/main/cpp/hoshidicts_jni.cpp"
)

target_include_directories(hoshidicts_jni PRIVATE
    "${HOSHIDICTS_ROOT}/include"
)

target_link_libraries(hoshidicts_jni PRIVATE
    hoshidicts
)
