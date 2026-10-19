cmake_minimum_required(VERSION 3.22.1)
project(noah_files C)
add_library(noah_files SHARED atomic_move.c)
target_link_options(noah_files PRIVATE "-Wl,-z,max-page-size=16384")
