cmake_minimum_required(VERSION 3.18)
project(quiche_jni LANGUAGES C)

# JNI sources — will be populated as we implement the wrapper
set(JNI_SOURCES
    kiche_jni.c
    quiche_config_jni.c
    quiche_conn_jni.c
    quiche_h3_jni.c
    helpers.c
)

add_library(quiche_jni SHARED ${JNI_SOURCES})

# Include JNI headers. JNI symbols are resolved by the host JVM at load time, so
# we only ever need the headers — never the jvm import library.
if (ANDROID)
    # The NDK already puts jni.h on the default include path.
elseif (WIN32)
    # Don't use find_package(JNI) here: CMake 3.31's FindJNI throws a
    # "Syntax error in cmake code ... foreach" from JAVA_APPEND_LIBRARY_DIRECTORIES
    # on the GitHub windows runner. Pull the headers straight from JAVA_HOME.
    if (NOT DEFINED JAVA_HOME OR JAVA_HOME STREQUAL "")
        set(JAVA_HOME "$ENV{JAVA_HOME}")
    endif ()
    file(TO_CMAKE_PATH "${JAVA_HOME}" JAVA_HOME)
    target_include_directories(quiche_jni PRIVATE
        "${JAVA_HOME}/include"
        "${JAVA_HOME}/include/win32")
else ()
    find_package(JNI COMPONENTS JVM REQUIRED)
    target_include_directories(quiche_jni PRIVATE ${JNI_INCLUDE_DIRS})
endif ()

# Include quiche headers
if(NOT DEFINED QUICHE_INCLUDE_DIR)
    set(QUICHE_INCLUDE_DIR ${CMAKE_CURRENT_LIST_DIR}/../../third_party/quiche/quiche/include)
endif()
target_include_directories(quiche_jni PRIVATE ${QUICHE_INCLUDE_DIR})

if(ANDROID)
    # libquiche.so is unpacked from :libquiche-android's AAR by Gradle and the
    # path passed in via LIBQUICHE_ANDROID_JNI_DIR.
    if(NOT DEFINED LIBQUICHE_ANDROID_JNI_DIR)
        message(FATAL_ERROR "LIBQUICHE_ANDROID_JNI_DIR is not set — run via Gradle.")
    endif()
    set(QUICHE_LIB ${LIBQUICHE_ANDROID_JNI_DIR}/${ANDROID_ABI}/libquiche.so)

    add_library(quiche SHARED IMPORTED)
    set_target_properties(quiche PROPERTIES IMPORTED_LOCATION ${QUICHE_LIB})

    # 16KB page size alignment for Android 15+ compatibility
    target_link_options(quiche_jni PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
    )

    target_link_libraries(quiche_jni PRIVATE quiche ${JNI_LIBRARIES} m)
else ()
    # Desktop JVM JNI links dynamically against libquiche.{dylib,so} / quiche.dll;
    # the path is provided by Gradle from the unpacked :libquiche-jvm artifact.
    if(NOT DEFINED QUICHE_LIB_PATH)
        message(FATAL_ERROR "QUICHE_LIB_PATH must point at the libquiche dynamic library — run via Gradle.")
    endif()
    add_library(quiche SHARED IMPORTED)
    set_target_properties(quiche PROPERTIES IMPORTED_LOCATION "${QUICHE_LIB_PATH}")

    if (WIN32)
        # CMake's SHARED IMPORTED on Windows needs the import library (.dll.lib)
        # at link time, separate from the runtime DLL given in IMPORTED_LOCATION.
        if(NOT DEFINED QUICHE_IMPLIB)
            message(FATAL_ERROR "QUICHE_IMPLIB must point at the import library (quiche.dll.lib) on Windows.")
        endif()
        set_target_properties(quiche PROPERTIES IMPORTED_IMPLIB "${QUICHE_IMPLIB}")
        # MSVC omits the "lib" prefix on DLLs; force it so the artifact is
        # libquiche_jni.dll — the exact name KicheLoader.load() looks up.
        set_target_properties(quiche_jni PROPERTIES PREFIX "lib")
        target_link_libraries(quiche_jni PRIVATE quiche)
    else()
        target_link_libraries(quiche_jni PRIVATE quiche m)
    endif()
endif()
