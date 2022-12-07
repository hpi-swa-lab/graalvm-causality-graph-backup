package com.oracle.graal.pointsto.reports;

public class ClassInitializationTracing {

    public static native Class<?> getResponsibleClass(Object imageHeapObject);

    public static native void onClinitRequested(Class<?> klass, boolean start);
}
