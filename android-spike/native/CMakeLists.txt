cmake_minimum_required(VERSION 3.22)
project(mixsphere_engine VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(MIXXX_ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." CACHE PATH "Root of the Mixxx-derived source tree")

if(DEFINED ENV{QT_ANDROID_CMAKE_PREFIX_PATH})
  list(APPEND CMAKE_PREFIX_PATH
    "$ENV{QT_ANDROID_CMAKE_PREFIX_PATH}"
    "$ENV{QT_ANDROID_CMAKE_PREFIX_PATH}/share/Qt6"
    "$ENV{QT_ANDROID_CMAKE_PREFIX_PATH}/lib/cmake/Qt6")
endif()
find_package(Qt6 6.2 REQUIRED COMPONENTS Core)

include(FetchContent)
FetchContent_Declare(
  oboe
  GIT_REPOSITORY https://github.com/google/oboe.git
  GIT_TAG 03242e9ef9495e418e6ae83954d239dc9193ec5c
)
set(OBOE_BUILD_UNIT_TESTS OFF CACHE BOOL "" FORCE)
set(OBOE_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(oboe)

add_library(mixsphere_engine SHARED
  jni_bridge.cpp
  DeckEngine.cpp
  OboeOutput.cpp
  WavReader.cpp
  StemSeparator.cpp
  ${MIXXX_ROOT}/src/engine/enginexfader.cpp
)

target_include_directories(mixsphere_engine PRIVATE
  ${CMAKE_CURRENT_LIST_DIR}
  ${MIXXX_ROOT}/src
  ${MIXXX_ROOT}/lib/fidlib
)

target_link_libraries(mixsphere_engine
  Qt6::Core
  oboe
  log
  android
)

if(ANDROID_ABI STREQUAL "arm64-v8a")
  target_compile_options(mixsphere_engine PRIVATE -march=armv8-a+simd)
endif()
