cmake_minimum_required(VERSION 3.18)
project(native_packager C)
add_library(native_packager SHARED native_packager.c)
target_link_libraries(native_packager android log)
