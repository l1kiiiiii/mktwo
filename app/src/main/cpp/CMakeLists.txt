# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("mktwo")

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
#
# In this top level CMakeLists.txt, ${CMAKE_PROJECT_NAME} is used to define
# the target library name; in the sub-module's CMakeLists.txt, ${PROJECT_NAME}
# is preferred for the same purpose.
#
# In order to load a library into your app from Java/Kotlin, you must call
# System.loadLibrary() and pass the name of the library defined here;
# for GameActivity/NativeActivity derived applications, the same library name must be
# used in the AndroidManifest.xml file.
add_library(${CMAKE_PROJECT_NAME} SHARED
    # List C/C++ source files with relative paths to this CMakeLists.txt.
    native-lib.cpp)

# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(${CMAKE_PROJECT_NAME}
    # List libraries link to the target library
    android
    log)

# --- Library for audio_matcher.cpp ---
# You can define multiple libraries.
add_library(mantra_matcher SHARED
        audio_matcher.cpp)

# Find the Android logging library (liblog) and store its path in the log-lib variable.
# This is an alternative way to link system libraries.
find_library(
        log-lib       # Sets the name of the path variable.
        log           # Specifies the name of the NDK library that
        # you want CMake to locate.
)

# Link mantra_matcher against the logging library.
# If log-lib is found, it will be linked. Otherwise, this might cause an error or warning.
if(log-lib)
    target_link_libraries(mantra_matcher ${log-lib})
else()
    message(WARNING "Android log library not found. mantra_matcher might not link correctly.")
    # You might choose to link directly to "log" here as well if find_library is just for demonstration
    # target_link_libraries(mantra_matcher log)
endif()

# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.