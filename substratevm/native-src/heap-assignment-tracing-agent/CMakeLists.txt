cmake_minimum_required(VERSION 3.22)
project(heap-assignment-tracing-agent)

set(JAVA_AWT_LIBRARY NotNeeded)
set(JAVA_AWT_INCLUDE_PATH NotNeeded)
find_package(JNI REQUIRED)
include_directories(${JNI_INCLUDE_DIRS})

set(CMAKE_CXX_STANDARD 20)

add_library(heap-assignment-tracing-agent SHARED main.cpp bytecode.cpp settings.h JvmtiWrapper.h)

target_link_libraries(heap-assignment-tracing-agent -static-libgcc -static-libstdc++)


add_custom_command(
        OUTPUT ${CMAKE_BINARY_DIR}/HeapAssignmentTracingHooks.class
        DEPENDS HeapAssignmentTracingHooks.java
        COMMAND javac HeapAssignmentTracingHooks.java -d ${CMAKE_BINARY_DIR}
        WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
)

add_custom_command(
        OUTPUT ${CMAKE_BINARY_DIR}/heap-assignment-tracing-agent-hooks.jar
        COMMAND jar cfM heap-assignment-tracing-agent-hooks.jar HeapAssignmentTracingHooks.class
        WORKING_DIRECTORY ${CMAKE_BINARY_DIR}
        DEPENDS HeapAssignmentTracingHooks.class
)

add_custom_target(HeapAssignmentTracingHooks ALL
        DEPENDS heap-assignment-tracing-agent-hooks.jar)
