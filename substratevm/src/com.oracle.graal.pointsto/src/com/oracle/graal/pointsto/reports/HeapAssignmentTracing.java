package com.oracle.graal.pointsto.reports;

import java.lang.reflect.Field;

public class HeapAssignmentTracing {
    private static HeapAssignmentTracing instance = new HeapAssignmentTracing();

    public static HeapAssignmentTracing getInstance() {
        return instance;
    }

    public static void activate() {
        instance = new NativeImpl();
    }

    public Object getResponsibleClass(Object imageHeapObject) {
        return null;
    }

    public Object getClassResponsibleForNonstaticFieldWrite(Object receiver, Field field, Object val) {
        return null;
    }

    public Object getClassResponsibleForStaticFieldWrite(Class<?> declaring, Field field, Object val) {
        return null;
    }

    public Object getClassResponsibleForArrayWrite(Object[] array, int index, Object val) {
        return null;
    }

    public Object getBuildTimeClinitResponsibleForBuildTimeClinit(Class<?> clazz) {
        return null;
    }


    protected void beginTracing(Object customReason) {
    }

    protected void endTracing(Object customReason) {
    }

    public final CustomTracingToken trace(Object customReason) {
        beginTracing(customReason);
        return new CustomTracingToken(customReason);
    }

    // Allows the simple usage of accountRootRegistrationsTo() in a try-with-resources statement
    public class CustomTracingToken implements AutoCloseable {
        private final Object reason;

        CustomTracingToken(Object reason) {
            this.reason = reason;
        }

        @Override
        public void close() {
            endTracing(reason);
        }
    }

    public void dispose() {}

    private static final class NativeImpl extends HeapAssignmentTracing {
        @Override
        public native Object getResponsibleClass(Object imageHeapObject);

        @Override
        public native Object getClassResponsibleForNonstaticFieldWrite(Object receiver, Field field, Object val);

        @Override
        public native Object getClassResponsibleForStaticFieldWrite(Class<?> declaring, Field field, Object val);

        @Override
        public native Object getClassResponsibleForArrayWrite(Object[] array, int index, Object val);

        @Override
        public native Object getBuildTimeClinitResponsibleForBuildTimeClinit(Class<?> clazz);

        @Override
        protected native void beginTracing(Object customReason);

        @Override
        protected native void endTracing(Object customReason);

        @Override
        public native void dispose();
    }
}
