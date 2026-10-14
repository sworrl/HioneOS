# libhionebeat.so: whole-track tempo (QM-DSP TempoTrackV2) and live beat tracking (BTrack).
# GPL: QM-DSP is GPL-2.0-or-later, BTrack GPL-3.0-or-later, kissfft BSD-3-Clause. See
# ATTRIBUTIONS.md at the repo root and the license files next to each vendored tree.
include(${CMAKE_CURRENT_LIST_DIR}/sources.cmake)

add_library(hionebeat SHARED ${HIONEBEAT_SOURCES} ${HIONEBEAT_DIR}/hione_beat_jni.cpp)
target_include_directories(hionebeat PRIVATE ${HIONEBEAT_INCLUDES})
target_compile_definitions(hionebeat PRIVATE ${HIONEBEAT_DEFINES})
target_compile_options(hionebeat PRIVATE -O2 -Wno-vla-extension -Wno-unused-parameter -Wno-sign-compare)
set_target_properties(hionebeat PROPERTIES DEBUG_POSTFIX "")
find_library(hionebeat-log-lib log)
target_link_libraries(hionebeat ${hionebeat-log-lib})
