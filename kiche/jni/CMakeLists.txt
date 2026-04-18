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

# Include JNI headers
if (NOT ANDROID)
    find_package(JNI COMPONENTS JVM REQUIRED)
endif ()
target_include_directories(quiche_jni PRIVATE ${JNI_INCLUDE_DIRS})

# Include quiche headers
if(NOT DEFINED QUICHE_INCLUDE_DIR)
    set(QUICHE_INCLUDE_DIR ${CMAKE_CURRENT_LIST_DIR}/../../third_party/quiche/quiche/include)
endif()
target_include_directories(quiche_jni PRIVATE ${QUICHE_INCLUDE_DIR})

if(ANDROID)
    set(PREBUILT_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../build/quiche/android)
    set(QUICHE_LIB ${PREBUILT_ROOT}/${ANDROID_ABI}/lib/libquiche.a)

    add_library(quiche STATIC IMPORTED)
    set_target_properties(quiche PROPERTIES IMPORTED_LOCATION ${QUICHE_LIB})

    # 16KB page size alignment for Android 15+ compatibility
    target_link_options(quiche_jni PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
    )

    target_link_libraries(quiche_jni PRIVATE quiche ${JNI_LIBRARIES} m)
else ()
    if(NOT DEFINED QUICHE_LIB_PATH)
        set(QUICHE_LIB_PATH ${CMAKE_CURRENT_LIST_DIR}/../../build/quiche/macosx/${CMAKE_SYSTEM_PROCESSOR}/lib/libquiche.a)
    endif()
    add_library(quiche STATIC IMPORTED)
    set_target_properties(quiche PROPERTIES IMPORTED_LOCATION "${QUICHE_LIB_PATH}")

    if (WIN32)
        target_link_libraries(quiche_jni PRIVATE ${JNI_LIBRARIES} quiche m)
    else()
        target_link_libraries(quiche_jni PRIVATE quiche m)
    endif()
endif()
