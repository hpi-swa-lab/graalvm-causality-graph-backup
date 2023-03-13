# Heap Assignment Tracing Agent

In order for the CausalityExport to be able to figure what happened during
class initialization at build-time, this agent traces Object-typed heap writes and
accounts them to the currently running class initializer.
Class initializers forcing the initialization of other classes are also recorded.

## Why is instrumentation necessary?

We need to find out when Class initialization starts, i.e. when the body of XXX.<clinit>() for any class XXX is run.
Additionally, array writes cannot be tracked using Watchpoints, so we need to stop at every aastore-Instruction.

1. Spamming "SetWatchpoint(...)" on thousands of fields keeps up a good performance. Breakpoints created using "SetBreakpoint(...)" however are really slow. Therefore instrumentation is needed.
2. Using the JVMTI function "SetBreakpoint(...)" seems to change the bytecode, such that the analysis won't inline code with those breakpoints. That prohibits its use for this purpose.
