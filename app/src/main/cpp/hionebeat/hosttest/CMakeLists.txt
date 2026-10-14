# Host build of the same analysis code, for the accuracy test (bpm_eval.py).
#   cmake -S app/src/main/cpp/hionebeat/hosttest -B /tmp/hionebeat-host && cmake --build /tmp/hionebeat-host
cmake_minimum_required(VERSION 3.16)
project(hionebeat_hosttest C CXX)
set(CMAKE_CXX_STANDARD 14)
include(${CMAKE_CURRENT_LIST_DIR}/../sources.cmake)
add_executable(bpm_hosttest ${HIONEBEAT_SOURCES} ${CMAKE_CURRENT_LIST_DIR}/bpm_hosttest.cpp)
target_include_directories(bpm_hosttest PRIVATE ${HIONEBEAT_INCLUDES})
target_compile_definitions(bpm_hosttest PRIVATE ${HIONEBEAT_DEFINES})
target_compile_options(bpm_hosttest PRIVATE -O2 -Wno-vla -Wno-unused-parameter)
target_link_libraries(bpm_hosttest m)
