cmake_minimum_required(VERSION 3.22.1)

project(bitperfectplayer_sacd C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_compile_options(-ffunction-sections -fdata-sections)
add_compile_options(-fvisibility=hidden)

set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/bridge
    ${THIRD_PARTY}/sacd/libsacd
    ${THIRD_PARTY}/sacd/libcommon
    ${THIRD_PARTY}/sacd/libdstdec
    ${THIRD_PARTY}/ffmpeg_dsd
    ${THIRD_PARTY}/ffmpeg_dsd/libavutil
)

set(SACD_LIBRARY_SOURCES
    ${THIRD_PARTY}/ffmpeg_dsd/dsd.c
    ${THIRD_PARTY}/ffmpeg_dsd/reverse.c
    ${THIRD_PARTY}/sacd/libsacd/sacd_reader.c
    ${THIRD_PARTY}/sacd/libsacd/sacd_input.c
    ${THIRD_PARTY}/sacd/libsacd/scarletbook_read.c
    ${THIRD_PARTY}/sacd/libsacd/scarletbook_helpers.c
    ${THIRD_PARTY}/sacd/libsacd/scarletbook_print.c
    ${THIRD_PARTY}/sacd/libsacd/scarletbook.c
    ${THIRD_PARTY}/sacd/libsacd/sacd_ripper.pb.c
    ${THIRD_PARTY}/sacd/libsacd/sacd_pb_stream.c
    ${THIRD_PARTY}/sacd/libdstdec/buffer_pool.c
    ${THIRD_PARTY}/sacd/libdstdec/ccp_calc.c
    ${THIRD_PARTY}/sacd/libdstdec/dst_ac.c
    ${THIRD_PARTY}/sacd/libdstdec/dst_data.c
    ${THIRD_PARTY}/sacd/libdstdec/dst_decoder.c
    ${THIRD_PARTY}/sacd/libdstdec/dst_fram.c
    ${THIRD_PARTY}/sacd/libdstdec/dst_init.c
    ${THIRD_PARTY}/sacd/libdstdec/unpack_dst.c
    ${THIRD_PARTY}/sacd/libdstdec/yarn.c
    ${THIRD_PARTY}/sacd/libcommon/fileutils.c
    ${THIRD_PARTY}/sacd/libcommon/log.c
    ${THIRD_PARTY}/sacd/libcommon/logging.c
    ${THIRD_PARTY}/sacd/libcommon/pb_decode.c
    ${THIRD_PARTY}/sacd/libcommon/pb_encode.c
    ${THIRD_PARTY}/sacd/libcommon/socket.c
    ${THIRD_PARTY}/sacd/libcommon/timeout.c
    ${THIRD_PARTY}/sacd/libcommon/usocket.c
    ${THIRD_PARTY}/sacd/libcommon/utils.c
    # charset.c is replaced: bionic libc has no iconv() implementation
    ${CMAKE_CURRENT_SOURCE_DIR}/bridge/charset_android.c
)

add_library(bitperfectplayer SHARED
    ${SACD_LIBRARY_SOURCES}
    ${CMAKE_CURRENT_SOURCE_DIR}/bridge/sacd_pcm.c
    ${CMAKE_CURRENT_SOURCE_DIR}/jni/sacd_jni.c
)

target_link_libraries(bitperfectplayer
    log
    m
)

target_link_options(bitperfectplayer PRIVATE
    -Wl,--gc-sections
)