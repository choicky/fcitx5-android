# PoC-only (local text post-processor feasibility): llama.cpp v0.6.0 CPU-only runtime,
# linked statically into one JNI facade library. Guarded by the parent CMakeLists to
# arm64-v8a and enabled only for BUILD_ABI=arm64-v8a builds; every other build of this
# repository produces exactly the previous native output, and the Kotlin side degrades
# to "no processor" when libpocpostprocessor.so is absent.
#
# Pin: gitlink app/src/main/cpp/llama.cpp = d81235049384534c167caea52b85a694f6103d14
# (tag v0.6.0), the same revision as the host probe evidence in raw-results/.
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)   # static libs linked into the facade .so
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)         # cross-compile: never -march=native
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp llama.cpp-build EXCLUDE_FROM_ALL)

# static archives must be position independent to link into libpocpostprocessor.so
foreach(t llama llama-common llama-common-base ggml ggml-base ggml-cpu)
    if (TARGET ${t})
        set_target_properties(${t} PROPERTIES POSITION_INDEPENDENT_CODE ON)
    endif()
endforeach()

add_library(poc-postprocessor SHARED poc_postprocessor.cpp)
target_link_libraries(poc-postprocessor
        log
        llama
        llama-common
        ggml
        ggml-base
        ggml-cpu)
