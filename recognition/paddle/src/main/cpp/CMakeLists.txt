cmake_minimum_required(VERSION 3.22.1)
project(fpink_paddle LANGUAGES CXX)

if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "The pinned Paddle runtime is Android ARM64 only.")
endif()

add_library(paddle_light_api_shared SHARED IMPORTED)
set_target_properties(paddle_light_api_shared PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../../../native/arm64-v8a/libpaddle_light_api_shared.so"
    INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_CURRENT_SOURCE_DIR}/third_party/paddle_lite")

add_library(fpink_paddle SHARED paddle_jni.cpp ocr_geometry.cpp)
target_compile_features(fpink_paddle PRIVATE cxx_std_17)
# Reproducible builds: F-Droid rebuilds this library and must get identical bytes. Keep build
# paths out of __FILE__ and debug info, and drop the lld build ID (a hash over the unstripped
# output, which AGP strips after the ID was computed).
target_compile_options(fpink_paddle PRIVATE -fvisibility=hidden "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")
target_link_options(fpink_paddle PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384" "LINKER:--build-id=none")
target_link_libraries(fpink_paddle PRIVATE paddle_light_api_shared log)
