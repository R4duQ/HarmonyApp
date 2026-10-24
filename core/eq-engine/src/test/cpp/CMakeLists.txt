# Host build of MobileEqEngine's tests (not part of the Android build):
#   cmake -S core/eq-engine/src/test/cpp -B build/eq-engine-test && cmake --build build/eq-engine-test
#   ./build/eq-engine-test/eq_engine_test
cmake_minimum_required(VERSION 3.16)
project(eq_engine_test CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()
find_package(Threads REQUIRED)
add_executable(eq_engine_test engine_test.cpp ../../main/cpp/MobileEqEngine.cpp)
target_include_directories(eq_engine_test PRIVATE ../../main/cpp)
target_compile_definitions(eq_engine_test PRIVATE HARMONY_EQ_TESTING)
target_compile_options(eq_engine_test PRIVATE -Wall -Wextra -Werror)
target_link_libraries(eq_engine_test PRIVATE Threads::Threads)

# The JNI library for the host JVM, so the Kotlin unit tests can run the real
# engine: point java.library.path (HARMONY_EQ_HOST_LIB_DIR) at this build directory.
find_package(JNI)
if(JNI_FOUND)
  add_library(harmonyeq SHARED ../../main/cpp/MobileEqEngine.cpp ../../main/cpp/eq_jni.cpp)
  target_include_directories(harmonyeq PRIVATE ../../main/cpp ${JNI_INCLUDE_DIRS})
  target_compile_options(harmonyeq PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
endif()
