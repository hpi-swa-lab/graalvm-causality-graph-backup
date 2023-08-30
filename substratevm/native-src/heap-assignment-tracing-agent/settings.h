#ifndef HEAP_ASSIGNMENT_TRACING_AGENT_SETTINGS_H
#define HEAP_ASSIGNMENT_TRACING_AGENT_SETTINGS_H

#define ASSERTIONS 1

#ifdef NDEBUG
#if ASSERTIONS
#define NDEBUG_DISABLED
#undef NDEBUG
#endif
#endif

#include <cassert>

#ifdef NDEBUG_DISABLED
#define NDEBUG
#undef NDEBUG_DISABLED
#endif

#define PRINT_CLINIT_HEAP_WRITES 0
#define LOG 0 // Medium verbose


#define REWRITE_ENABLE 1
// THis option is relevant in order to be able to debug the Java process with the rewriting functionality
#define BREAKPOINTS_ENABLE 1

#define HOOK_CLASS_NAME "HeapAssignmentTracingHooks"

#define HOOK_JAR_NAME "heap-assignment-tracing-agent-hooks.jar"
#define AGENT_LIBRARY_NAME "libheap-assignment-tracing-agent.so"

#endif //HEAP_ASSIGNMENT_TRACING_AGENT_SETTINGS_H
