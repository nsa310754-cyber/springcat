cmake_minimum_required(VERSION 3.22.1)
project("switchrec")

add_library(v4l2capture SHARED v4l2capture.c)

find_library(log-lib log)
target_link_libraries(v4l2capture ${log-lib})
