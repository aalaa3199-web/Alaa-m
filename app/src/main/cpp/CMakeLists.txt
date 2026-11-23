cmake_minimum_required(VERSION 3.22.1)
project("alaa_net_native")

add_library(
    alaa_net_native
    SHARED
    native_net.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    alaa_net_native
    ${log-lib}
)
