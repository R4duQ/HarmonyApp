# Host build of SmartShuffleEngine's tests (not part of the Android build):
#   cmake -S core/dsp-native/src/test/cpp -B build/shuffle-host && cmake --build build/shuffle-host
#   ./build/shuffle-host/shuffle_test
cmake_minimum_required(VERSION 3.16)
project(shuffle_test CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()
add_executable(shuffle_test shuffle_test.cpp ../../main/cpp/SmartShuffleEngine.cpp)
target_include_directories(shuffle_test PRIVATE ../../main/cpp)
target_compile_definitions(shuffle_test PRIVATE HARMONY_SHUFFLE_TESTING)
target_compile_options(shuffle_test PRIVATE -Wall -Wextra -Werror)

