cmake_minimum_required(VERSION 3.22.1)

project("projectM-native")

# --- libprojectM 4.2.0 (real) build options, set before add_subdirectory ---
set(ENABLE_SYSTEM_GLM OFF CACHE BOOL "" FORCE)
set(ENABLE_SYSTEM_PROJECTM_EVAL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)     # static, linked into our .so
set(ENABLE_PLAYLIST ON CACHE BOOL "" FORCE)
set(ENABLE_SDL_UI OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(ENABLE_GLES ON CACHE BOOL "" FORCE)
set(ENABLE_DEBUG_POSTFIX OFF CACHE BOOL "" FORCE)

# NOTE: vendor/projectm is a SYMLINK to an external checkout (~/Documents/GitHub/projectm).
# Gradle's up-to-date check does NOT traverse it, so after pulling a new projectM revision the
# native task is reported UP-TO-DATE and ninja is never invoked - you get a silently stale .so
# (hit 2026-09-12 updating 4.2.0-dev -> master 1e7ef780). After ANY projectM update run:
#     rm -rf app/.cxx app/build/intermediates/cmake
# then rebuild, and confirm a fresh PCM.cpp.o timestamp before trusting the result.
add_subdirectory(vendor/projectm ${CMAKE_BINARY_DIR}/projectm_build)

add_library(
        projectM-native
        SHARED
        projectm_native.cpp
        libm500audio_hal.cpp
)

# projectM's CMake sets a global CMAKE_DEBUG_POSTFIX="d"; keep OUR lib name clean
# so System.loadLibrary("projectM-native") resolves to libprojectM-native.so.
set_target_properties(projectM-native PROPERTIES DEBUG_POSTFIX "")

find_library(log-lib log)
find_library(gles3-lib GLESv3)
find_library(egl-lib EGL)
find_library(android-lib android)

target_link_libraries(
        projectM-native
        projectM
        projectM_playlist
        ${log-lib}
        ${gles3-lib}
        ${egl-lib}
        ${android-lib}
)

# Tempo and beat tracking (QM-DSP TempoTrackV2 + BTrack), its own libhionebeat.so.
add_subdirectory(hionebeat ${CMAKE_BINARY_DIR}/hionebeat_build)
