cmake_minimum_required(VERSION 3.22)
project(rumitx_llama_jni LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(LLAMA_ROOT "" CACHE PATH "Pinned llama.cpp source tree")
set(PREBUILT_DIR "" CACHE PATH "Directory containing Android ARM64 .so files")
if(NOT EXISTS "${LLAMA_ROOT}/include/llama.h")
  message(FATAL_ERROR "Missing pinned llama.cpp headers")
endif()
foreach(name llama ggml ggml-base ggml-cpu)
  add_library(${name} SHARED IMPORTED)
  set_target_properties(${name} PROPERTIES IMPORTED_LOCATION "${PREBUILT_DIR}/lib${name}.so")
endforeach()
add_library(rumitx_llama_jni SHARED rumitx_llama_jni.cpp)
target_include_directories(rumitx_llama_jni PRIVATE
  "${LLAMA_ROOT}/include"
  "${LLAMA_ROOT}/ggml/include"
)
target_compile_options(rumitx_llama_jni PRIVATE -fexceptions -frtti -O2)
target_link_libraries(rumitx_llama_jni PRIVATE llama ggml ggml-base ggml-cpu log)
set_target_properties(rumitx_llama_jni PROPERTIES
  LIBRARY_OUTPUT_DIRECTORY "${PREBUILT_DIR}"
  BUILD_RPATH "$ORIGIN"
)
