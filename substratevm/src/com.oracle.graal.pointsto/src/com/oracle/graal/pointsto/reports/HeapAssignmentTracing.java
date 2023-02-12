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

    public Class<?> getResponsibleClass(Object imageHeapObject) {
        return null;
    }

    public Class<?> getClassResponsibleForNonstaticFieldWrite(Object receiver, Field field, Object val) {
        return null;
    }

    public Class<?> getClassResponsibleForStaticFieldWrite(Class<?> declaring, Field field, Object val) {
        return null;
    }

    public Class<?> getClassResponsibleForArrayWrite(Object[] array, int index, Object val) {
        return null;
    }

    public Class<?> getBuildTimeClinitResponsibleForBuildTimeClinit(Class<?> clazz) {
        return null;
    }

    public void dispose() {}

    private static final class NativeImpl extends HeapAssignmentTracing {
        @Override
        public native Class<?> getResponsibleClass(Object imageHeapObject);

        @Override
        public native Class<?> getClassResponsibleForNonstaticFieldWrite(Object receiver, Field field, Object val);

        @Override
        public native Class<?> getClassResponsibleForStaticFieldWrite(Class<?> declaring, Field field, Object val);

        @Override
        public native Class<?> getClassResponsibleForArrayWrite(Object[] array, int index, Object val);

        @Override
        public native Class<?> getBuildTimeClinitResponsibleForBuildTimeClinit(Class<?> clazz);

        @Override
        public native void dispose();
    }
}
