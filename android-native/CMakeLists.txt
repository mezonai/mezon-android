cmake_minimum_required(VERSION 3.10)

# 1. Define the paths to your static libraries from the Makefile
set(MEZON_LIB_DIR ${CMAKE_SOURCE_DIR}/../../../out/android/${ANDROID_ABI})

# 2. Import your Mezon Static Lib
add_library(mezon_static STATIC IMPORTED)
set_target_properties(mezon_static PROPERTIES IMPORTED_LOCATION 
    ${MEZON_LIB_DIR}/libmezon_client.a)

# 4. Create the JSI Wrapper Library
add_library(mezon_jni SHARED mezon_jni_wrapper.cpp)

# 5. Link everything together
target_include_directories(mezon_jni PRIVATE 
    ${CMAKE_SOURCE_DIR}/../../../include)

target_link_libraries(mezon_jni
    mezon_static
    log
    android)