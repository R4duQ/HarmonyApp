# Host tests of the iOS player core (no Apple frameworks needed):
#   cmake -S ios/Tests -B build/ios-core-test && cmake --build build/ios-core-test
#   ./build/ios-core-test/player_core_test
cmake_minimum_required(VERSION 3.16)
project(ios_player_core_test CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()
find_package(Threads REQUIRED)
add_executable(player_core_test
  player_core_test.cpp
  ../Harmony/Audio/HarmonyPlayerCore.cpp
  ../../core/eq-engine/src/main/cpp/MobileEqEngine.cpp
  ../../core/dsp-native/src/main/cpp/SmartShuffleEngine.cpp)
target_include_directories(player_core_test PRIVATE
  ../Harmony/Audio
  ../../core/eq-engine/src/main/cpp
  ../../core/dsp-native/src/main/cpp)
target_compile_options(player_core_test PRIVATE -Wall -Wextra -Werror)
target_link_libraries(player_core_test PRIVATE Threads::Threads)

add_executable(tag_writer_test tag_writer_test.cpp ../Harmony/Ingest/HarmonyTagWriter.cpp)
target_include_directories(tag_writer_test PRIVATE ../Harmony/Ingest)
target_compile_options(tag_writer_test PRIVATE -Wall -Wextra -Werror)

find_package(ZLIB REQUIRED)
add_executable(slsk_test slsk_test.cpp
  ../Harmony/Soulseek/SlskProtocol.cpp
  ../Harmony/Soulseek/SlskEngine.cpp
  ../Harmony/Ingest/HarmonyTagWriter.cpp)
target_include_directories(slsk_test PRIVATE ../Harmony/Soulseek ../Harmony/Ingest)
target_compile_options(slsk_test PRIVATE -Wall -Wextra -Werror)
target_link_libraries(slsk_test PRIVATE ZLIB::ZLIB Threads::Threads)
