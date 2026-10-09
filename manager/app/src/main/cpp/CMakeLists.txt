
# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.
cmake_minimum_required(VERSION 3.18.1)

project("camjni")

find_package(cxx REQUIRED CONFIG)
link_libraries(cxx::cxx)

add_library(camjni
        SHARED
        jni.cc
        ksu.cc
        )

target_include_directories(camjni PRIVATE .)

find_library(log-lib log)

target_link_libraries(camjni ${log-lib})

add_library(adbroot SHARED adbroot.cc)
target_link_libraries(adbroot ${log-lib})

# raw syscalls for the isolated root hiding probe
add_library(hidingprobe SHARED hiding_probe.cc)
