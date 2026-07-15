# wxarchive: native tar + zstd, no libarchive.
cmake_minimum_required(VERSION 3.22.1)
project(wxarchive LANGUAGES C CXX)

include(FetchContent)

FetchContent_Declare(
    zstd
    URL https://github.com/facebook/zstd/archive/refs/tags/v1.5.6.tar.gz
    URL_HASH SHA256=30f35f71c1203369dc979ecde0400ffea93c27391bfd2ac5a9715d2173d92ff7
    SOURCE_SUBDIR build/cmake
)
set(ZSTD_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_TESTS OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(zstd)
add_library(zstd_static ALIAS libzstd_static)

add_library(wxarchive SHARED wxarchive.cpp)
target_compile_features(wxarchive PRIVATE cxx_std_17)
target_compile_options(wxarchive PRIVATE -Wall -Wextra -Werror)
target_link_libraries(wxarchive PRIVATE zstd_static log)
