include(FetchContent)

# SQLite 3.54.1 amalgamation. The archive matches sqlite.org's published
# SHA3-256 92281c86e2957f9b9305890f9ac04204caa7b677cc868dc326d942689df28e30.
FetchContent_Declare(hoshi_local_audio_amalgamation
    URL https://sqlite.org/2026/sqlite-amalgamation-3540100.zip
    URL_HASH SHA256=0a9c8e34352dad46006aa82836d5383bff9dc6bc9ce5a60553c05aa30e6ad1f9
    TLS_VERIFY TRUE
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(hoshi_local_audio_amalgamation)

add_library(hoshi_local_audio_sqlite STATIC "${hoshi_local_audio_amalgamation_SOURCE_DIR}/sqlite3.c")
set_target_properties(hoshi_local_audio_sqlite PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(hoshi_local_audio_sqlite PUBLIC "${hoshi_local_audio_amalgamation_SOURCE_DIR}")
target_compile_definitions(hoshi_local_audio_sqlite PRIVATE
    _FILE_OFFSET_BITS=64
    SQLITE_OMIT_LOAD_EXTENSION
    SQLITE_DEFAULT_MEMSTATUS=0
    SQLITE_DQS=0
    SQLITE_TEMP_STORE=3
    SQLITE_MAX_MMAP_SIZE=0
    SQLITE_OMIT_SHARED_CACHE
)
target_compile_options(hoshi_local_audio_sqlite PRIVATE -Os -fvisibility=hidden -ffunction-sections -fdata-sections)

add_library(hoshi_local_audio STATIC database.cpp fd_vfs.cpp)
set_target_properties(hoshi_local_audio PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(hoshi_local_audio PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_features(hoshi_local_audio PUBLIC cxx_std_20)
target_compile_definitions(hoshi_local_audio PRIVATE _FILE_OFFSET_BITS=64)
target_compile_options(hoshi_local_audio PRIVATE -fvisibility=hidden -Wall -Wextra -Werror)
target_link_libraries(hoshi_local_audio PRIVATE hoshi_local_audio_sqlite)

if(ANDROID)
    target_compile_definitions(hoshi_local_audio_sqlite PRIVATE SQLITE_OS_OTHER=1)
    add_library(hoshilocalaudio_jni SHARED jni.cpp)
    target_compile_options(hoshilocalaudio_jni PRIVATE -fvisibility=hidden -Wall -Wextra -Werror)
    target_link_options(hoshilocalaudio_jni PRIVATE
        -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,--no-undefined
    )
    target_link_libraries(hoshilocalaudio_jni PRIVATE hoshi_local_audio m)
else()
    find_package(Threads REQUIRED)
    target_link_libraries(hoshi_local_audio_sqlite PRIVATE Threads::Threads m)
endif()
