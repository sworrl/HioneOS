cmake_minimum_required(VERSION 3.22.1)

project("fmprojectm")

# projectM background for the FM tuner (com.caf.fmradio).
#
# The library sources are NOT copied here. They are the same vendored libprojectM 4.2 tree that
# Hione Music builds (app/src/main/cpp/vendor/projectm, itself a symlink to an external checkout),
# referenced by relative path so there is one copy of the vendor tree in the repo. Only the JNI
# wrapper is ours; the app's wrapper is bound to com.hione.player.ProjectMNative and cannot be
# reused across packages.
#
# Same staleness trap as the app: Gradle does not look inside the symlinked vendor dir, so after
# a projectM update run `rm -rf fmradio/.cxx fmradio/build/intermediates/cxx` before trusting a
# rebuild.
set(FM_PROJECTM_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../app/src/main/cpp/vendor/projectm")
get_filename_component(FM_PROJECTM_DIR "${FM_PROJECTM_DIR}" REALPATH)
if(NOT EXISTS "${FM_PROJECTM_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "projectM sources not found at ${FM_PROJECTM_DIR}")
endif()

# Same build options as the app (static libprojectM folded into our one .so, GLES, no extras).
set(ENABLE_SYSTEM_GLM OFF CACHE BOOL "" FORCE)
set(ENABLE_SYSTEM_PROJECTM_EVAL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ENABLE_PLAYLIST ON CACHE BOOL "" FORCE)
set(ENABLE_SDL_UI OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(ENABLE_GLES ON CACHE BOOL "" FORCE)
set(ENABLE_DEBUG_POSTFIX OFF CACHE BOOL "" FORCE)

add_subdirectory("${FM_PROJECTM_DIR}" "${CMAKE_BINARY_DIR}/projectm_build")

add_library(fmprojectm SHARED fm_projectm_jni.cpp)

# projectM's CMake sets a global CMAKE_DEBUG_POSTFIX="d"; keep our lib name stable.
set_target_properties(fmprojectm PROPERTIES DEBUG_POSTFIX "")

# The vendor .so in jniLibs (libqcomfm_jni.so) is loaded alongside this one. Keep every symbol
# that is not a JNI entry point private so the two can never interpose on each other.
set_target_properties(fmprojectm PROPERTIES C_VISIBILITY_PRESET hidden CXX_VISIBILITY_PRESET hidden)
target_link_options(fmprojectm PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,--gc-sections")

find_library(log-lib log)
find_library(gles3-lib GLESv3)
find_library(egl-lib EGL)

target_link_libraries(
        fmprojectm
        projectM
        projectM_playlist
        ${log-lib}
        ${gles3-lib}
        ${egl-lib}
)
