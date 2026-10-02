cmake_minimum_required(VERSION 3.18)
project(mezon_ns LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT EXISTS "${ONNXRUNTIME_ROOT}/headers/onnxruntime_cxx_api.h")
    message(FATAL_ERROR "ONNX Runtime Android headers missing at ${ONNXRUNTIME_ROOT}")
endif()

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION "${ONNXRUNTIME_ROOT}/jni/${ANDROID_ABI}/libonnxruntime.so")

add_library(mezon_ns SHARED
    mezon_ns/mezon_ns_c_api.cpp
    mezon_ns/mezon_ns_engine.cpp
    mezon_ns/mezon_ns_48k.cpp
    mezon_ns/mezon_ns_jni.cpp)
target_include_directories(mezon_ns PRIVATE mezon_ns "${ONNXRUNTIME_ROOT}/headers")
target_compile_options(mezon_ns PRIVATE -O3 -fvisibility=hidden)
target_link_libraries(mezon_ns PRIVATE onnxruntime log)
