cmake_minimum_required(VERSION 3.22.1)

project("springcat")

# Build our C++ source into a shared library that the app loads at runtime.
add_library(springcat SHARED
        native-lib.cpp)

# Link against Android's logging library.
find_library(log-lib log)
target_link_libraries(springcat ${log-lib})
