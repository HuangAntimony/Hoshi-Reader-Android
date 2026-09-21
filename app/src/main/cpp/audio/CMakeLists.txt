include(ExternalProject)
find_program(HOSHI_MAKE NAMES gmake make REQUIRED)

# Build only local audiobook demuxing, decoding and resampling. No FFmpeg CLI,
# network protocols, encoders, video, GPL components, or external codec libraries.
set(FFMPEG_VERSION 9.0.2)
set(FFMPEG_PREFIX "${CMAKE_CURRENT_BINARY_DIR}/ffmpeg")
get_filename_component(NDK_BIN "${CMAKE_C_COMPILER}" DIRECTORY)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(FFMPEG_ARCH aarch64)
    set(FFMPEG_TARGET aarch64-linux-android)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(FFMPEG_ARCH x86_64)
    set(FFMPEG_TARGET x86_64-linux-android)
    # Emulator builds need no separately installed NASM. ARM retains NEON.
    set(FFMPEG_ARCH_OPTIONS --disable-x86asm)
else()
    message(FATAL_ERROR "Unsupported transcription ABI: ${ANDROID_ABI}")
endif()

ExternalProject_Add(hoshi_ffmpeg
    URL "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"
    URL_HASH SHA256=8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    PREFIX "${FFMPEG_PREFIX}"
    CONFIGURE_COMMAND <SOURCE_DIR>/configure
        --target-os=android --arch=${FFMPEG_ARCH} --enable-cross-compile
        "--cc=${CMAKE_C_COMPILER} --target=${FFMPEG_TARGET}${ANDROID_PLATFORM_LEVEL}"
        "--cxx=${CMAKE_CXX_COMPILER} --target=${FFMPEG_TARGET}${ANDROID_PLATFORM_LEVEL}"
        --ar=${NDK_BIN}/llvm-ar --ranlib=${NDK_BIN}/llvm-ranlib
        --strip=${NDK_BIN}/llvm-strip --nm=${NDK_BIN}/llvm-nm
        --prefix=<INSTALL_DIR>
        --disable-everything --disable-autodetect --disable-network
        --disable-doc --disable-programs --disable-avdevice --disable-avfilter --disable-swscale
        --disable-debug --enable-pic --enable-static --disable-shared
        --enable-avformat --enable-avcodec --enable-swresample
        --enable-demuxer=mov,mp3,ogg,wav,aac,flac
        --enable-decoder=aac,mp3float,opus,vorbis,alac,flac,pcm_u8,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,pcm_f64le
        --enable-parser=aac,mpegaudio,opus,vorbis,flac
        "--extra-cflags=-O3 -ffunction-sections -fdata-sections"
        ${FFMPEG_ARCH_OPTIONS}
    BUILD_COMMAND ${HOSHI_MAKE} -j4
    INSTALL_COMMAND ${HOSHI_MAKE} install
    BUILD_BYPRODUCTS
        "${FFMPEG_PREFIX}/lib/libavformat.a"
        "${FFMPEG_PREFIX}/lib/libavcodec.a"
        "${FFMPEG_PREFIX}/lib/libswresample.a"
        "${FFMPEG_PREFIX}/lib/libavutil.a"
    LOG_DOWNLOAD TRUE LOG_CONFIGURE TRUE LOG_BUILD TRUE LOG_INSTALL TRUE
    LOG_OUTPUT_ON_FAILURE TRUE
)

file(MAKE_DIRECTORY "${FFMPEG_PREFIX}/include")
foreach(component avformat avcodec swresample avutil)
    add_library(hoshi_${component} STATIC IMPORTED)
    set_target_properties(hoshi_${component} PROPERTIES
        IMPORTED_LOCATION "${FFMPEG_PREFIX}/lib/lib${component}.a"
        INTERFACE_INCLUDE_DIRECTORIES "${FFMPEG_PREFIX}/include"
    )
    add_dependencies(hoshi_${component} hoshi_ffmpeg)
endforeach()

add_library(hoshiaudio_jni SHARED audio_decoder.cpp)
add_dependencies(hoshiaudio_jni hoshi_ffmpeg)
target_compile_options(hoshiaudio_jni PRIVATE -O3 -fvisibility=hidden -Wall -Wextra)
target_link_options(hoshiaudio_jni PRIVATE -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
target_link_libraries(hoshiaudio_jni PRIVATE hoshi_avformat hoshi_avcodec hoshi_swresample hoshi_avutil m)
