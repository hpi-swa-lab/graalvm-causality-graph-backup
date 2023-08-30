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

    public void setCause(Object cause, boolean recordHeapAssignments) {
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
        public native void setCause(Object cause, boolean recordHeapAssignments);

        @Override
        public native void dispose();
    }
}
