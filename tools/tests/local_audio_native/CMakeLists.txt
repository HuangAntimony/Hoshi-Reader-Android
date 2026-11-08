cmake_minimum_required(VERSION 3.22.1)
project(hoshi_local_audio_tests LANGUAGES C CXX)

enable_testing()
add_subdirectory(../../../app/src/main/cpp/localaudio localaudio)
add_executable(local_audio_native_test local_audio_native_test.cpp)
target_compile_options(local_audio_native_test PRIVATE -Wall -Wextra -Werror)
target_link_libraries(local_audio_native_test PRIVATE hoshi_local_audio hoshi_local_audio_sqlite)
add_test(NAME local_audio_native COMMAND local_audio_native_test)
