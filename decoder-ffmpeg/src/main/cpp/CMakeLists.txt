# Media3 FFmpeg JNI wrapper (Apache 2.0, from androidx/media 1.8.0) linked DYNAMICALLY against FFmpeg (LGPL 2.1),
# whose shared libraries are built by ../../../build_ffmpeg.sh into src/main/jniLibs/<abi>.
cmake_minimum_required(VERSION 3.21.0 FATAL_ERROR)
set(CMAKE_CXX_STANDARD 11)
project(libffmpegJNI C CXX)

set(ffmpeg_include "${CMAKE_CURRENT_SOURCE_DIR}/../../../ffmpeg/include")
set(ffmpeg_libs "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

foreach(ffmpeg_lib avutil swresample avcodec)
    add_library(${ffmpeg_lib} SHARED IMPORTED)
    set_target_properties(${ffmpeg_lib} PROPERTIES IMPORTED_LOCATION ${ffmpeg_libs}/lib${ffmpeg_lib}.so)
endforeach()

include_directories(${ffmpeg_include})
find_library(android_log_lib log)

add_library(ffmpegJNI SHARED ffmpeg_jni.cc)
target_link_libraries(ffmpegJNI PRIVATE android PRIVATE swresample PRIVATE avcodec PRIVATE avutil PRIVATE ${android_log_lib})

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_options(ffmpegJNI PRIVATE "-Wl,-Bsymbolic")
endif()
# 16 KB ELF alignment (Google Play requirement for Android 15+).
target_link_options(ffmpegJNI PRIVATE "-Wl,-z,max-page-size=16384")
