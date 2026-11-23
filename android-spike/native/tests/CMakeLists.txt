cmake_minimum_required(VERSION 3.22)
project(mixsphere_engine_tests LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(
  MIXXX_ROOT
  "${CMAKE_CURRENT_LIST_DIR}/../../.."
  CACHE PATH
  "Root of the Mixxx-derived source tree"
)

enable_testing()

find_package(Qt6 6.2 REQUIRED COMPONENTS Core)
find_package(GTest REQUIRED)
include(GoogleTest)

add_executable(
  engine_tests
  engine_tests.cpp
  ../DeckEngine.cpp
  ../WavReader.cpp
  ${MIXXX_ROOT}/src/engine/enginexfader.cpp
)

target_include_directories(
  engine_tests
  PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}/..
    ${MIXXX_ROOT}/src
    ${MIXXX_ROOT}/lib/fidlib
)

target_link_libraries(engine_tests Qt6::Core GTest::gtest GTest::gtest_main)
gtest_discover_tests(engine_tests)
