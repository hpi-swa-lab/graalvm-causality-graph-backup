package com.oracle.graal.pointsto.reports;

import java.lang.reflect.Field;

public class ClassInitializationTracing {

    public static native Class<?> getResponsibleClass(Object imageHeapObject);

    public static native void onClinitRequested(Class<?> klass, boolean start);

    public static native Class<?> getClassResponsibleForNonstaticFieldWrite(Object receiver, Field field, Object val);

    public static native Class<?> getClassResponsibleForStaticFieldWrite(Class<?> declaring, Field field, Object val);

    public static native Class<?> getClassResponsibleForArrayWrite(Object[] array, int index, Object val);
}
