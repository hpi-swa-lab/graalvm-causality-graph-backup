package com.oracle.graal.pointsto.reports;

import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.flow.AbstractVirtualInvokeTypeFlow;
import com.oracle.graal.pointsto.flow.MethodTypeFlow;
import com.oracle.graal.pointsto.flow.TypeFlow;
import com.oracle.graal.pointsto.meta.AnalysisElement;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.reports.causality.EmptyImpl;
import com.oracle.graal.pointsto.reports.causality.Impl;
import com.oracle.graal.pointsto.reports.causality.Graph;
import com.oracle.graal.pointsto.typestate.TypeState;
import jdk.vm.ci.meta.JavaConstant;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public abstract class CausalityExport {
    private static final EmptyImpl dummyInstance = new EmptyImpl();
    private static ThreadLocal<Impl> instances;
    private static List<Impl> instancesOfAllThreads;

    // Starts collection of Causality Data
    public static synchronized void activate() {
        instances = ThreadLocal.withInitial(CausalityExport::createInstance);
        instancesOfAllThreads = new ArrayList<>();
    }

    private static synchronized Impl createInstance() {
        Impl instance = new Impl();
        instancesOfAllThreads.add(instance);
        return instance;
    }

    public static CausalityExport getInstance() {
        return instances != null ? instances.get() : dummyInstance;
    }

    public synchronized void dump(PointsToAnalysis bb) throws java.io.IOException {
        Impl data = new Impl(instancesOfAllThreads, bb);
        // Let GC collect intermediate data structures
        instances = null;
        instancesOfAllThreads = null;
        Graph g = data.createCausalityGraph(bb);
        g.export(bb);
    }


    // --- Registration ---
    public abstract void addTypeFlowEdge(TypeFlow<?> from, TypeFlow<?> to);

    protected abstract void beginSaturationHappening();

    protected abstract void endSaturationHappening();

    public final SaturationHappeningToken setSaturationHappening()
    {
        beginSaturationHappening();
        return new SaturationHappeningToken();
    }

    public class SaturationHappeningToken implements AutoCloseable {
        @Override
        public void close() {
            endSaturationHappening();
        }

        SaturationHappeningToken() { }
    }

    public abstract void registerTypesFlowing(PointsToAnalysis bb, Reason reason, TypeFlow<?> destination, TypeState types);

    public abstract void register(Reason reason, Reason consequence);

    public abstract void addVirtualInvoke(PointsToAnalysis bb, AbstractVirtualInvokeTypeFlow invocation, AnalysisMethod concreteTargetMethod, TypeState concreteTargetMethodCallingTypes);

    public abstract void registerMethodFlow(MethodTypeFlow method);

    public abstract void registerVirtualInvokeTypeFlow(AbstractVirtualInvokeTypeFlow invocation);

    public abstract Reason getReasonForHeapObject(PointsToAnalysis bb, JavaConstant heapObject);

    public abstract Reason getReasonForHeapFieldAssignment(PointsToAnalysis analysis, JavaConstant receiver, AnalysisField field, JavaConstant value);

    public abstract Reason getReasonForHeapArrayAssignment(PointsToAnalysis analysis, JavaConstant array, int elementIndex, JavaConstant value);

    public final ReRootingToken accountRootRegistrationsTo(Reason reason) {
        beginAccountingRootRegistrationsTo(reason);
        return new ReRootingToken(reason);
    }

    // May be unrooted due to an ongoing accountRootRegistrationsTo(...)
    public abstract void registerReasonRoot(Reason reason);

    protected abstract void beginAccountingRootRegistrationsTo(Reason reason);

    protected abstract void endAccountingRootRegistrationsTo(Reason reason);

    // Allows the simple usage of accountRootRegistrationsTo() in a try-with-resources statement
    public class ReRootingToken implements AutoCloseable {
        private final Reason reason;

        ReRootingToken(Reason reason) {
            this.reason = reason;
        }

        @Override
        public void close() {
            endAccountingRootRegistrationsTo(reason);
        }
    }




    private static String reflectionObjectToString(Object reflectionObject)
    {
        if(reflectionObject instanceof Class<?>) {
            return ((Class<?>) reflectionObject).getTypeName();
        } else if(reflectionObject instanceof Constructor<?>) {
            Constructor<?> c = ((Constructor<?>) reflectionObject);
            return c.getDeclaringClass().getTypeName() + ".<init>(" + Arrays.stream(c.getParameterTypes()).map(Class::getTypeName).collect(Collectors.joining(", ")) + ')';
        } else if(reflectionObject instanceof Method) {
            Method m = ((Method) reflectionObject);
            return m.getDeclaringClass().getTypeName() + '.' + m.getName() + '(' + Arrays.stream(m.getParameterTypes()).map(Class::getTypeName).collect(Collectors.joining(", ")) + ')';
        } else {
            Field f = ((Field) reflectionObject);
            return f.getDeclaringClass().getTypeName() + '.' + f.getName();
        }
    }

    public static abstract class Reason {
        public boolean unused() {
            return false;
        }

        public boolean root() { return false; }
    }

    public static abstract class ReachableReason<T extends AnalysisElement> extends Reason {
        public final T element;

        public ReachableReason(T element) {
            this.element = element;
        }

        public static ReachableReason<?> create(AnalysisElement e) {
            if(e instanceof AnalysisMethod)
                return new MethodReachableReason((AnalysisMethod) e);
            if(e instanceof AnalysisType)
                return new TypeReachableReason((AnalysisType) e);
            throw new IllegalArgumentException();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ReachableReason<?> that = (ReachableReason<?>) o;
            return element.equals(that.element);
        }

        @Override
        public int hashCode() {
            return Objects.hash(element);
        }
    }

    public static final class MethodReachableReason extends ReachableReason<AnalysisMethod> {
        public MethodReachableReason(AnalysisMethod method) {
            super(method);
        }

        @Override
        public String toString() {
            return element.getQualifiedName();
        }

        @Override
        public boolean unused() {
            return !element.isImplementationInvoked();
        }
    }

    public static final class TypeReachableReason extends ReachableReason<AnalysisType> {
        public TypeReachableReason(AnalysisType type) {
            super(type);
        }

        @Override
        public String toString() {
            return element.toJavaName();
        }

        @Override
        public boolean unused() {
            return !element.isReachable();
        }
    }

    public static final class TypeInstantiatedReason extends Reason {
        public final AnalysisType type;

        public TypeInstantiatedReason(AnalysisType type) {
            this.type = type;
        }

        @Override
        public boolean unused() {
            return !type.isInstantiated();
        }

        @Override
        public String toString() {
            return "Type Instantiated: " + type.toJavaName();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TypeInstantiatedReason that = (TypeInstantiatedReason) o;
            return type.equals(that.type);
        }

        @Override
        public int hashCode() {
            return type.hashCode();
        }
    }

    public static abstract class CustomReason extends Reason {
    }

    public static class JNIRegistration extends CustomReason {
        public final Object element;

        public JNIRegistration(Executable method) {
            this.element = method;
        }

        public JNIRegistration(Field field) {
            this.element = field;
        }

        public JNIRegistration(Class<?> clazz) {
            this.element = clazz;
        }

        @Override
        public String toString() {
            return "JNI registration: " + reflectionObjectToString(element);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            JNIRegistration that = (JNIRegistration) o;
            return element.equals(that.element);
        }

        @Override
        public int hashCode() {
            return element.hashCode();
        }
    }

    public static class ReflectionRegistration extends CustomReason {
        public final Object element;

        public ReflectionRegistration(AccessibleObject methodOrField) {
            this.element = methodOrField;
        }

        public ReflectionRegistration(Class<?> clazz) {
            this.element = clazz;
        }

        @Override
        public String toString() {
            return "Reflection registration: " + reflectionObjectToString(element);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ReflectionRegistration that = (ReflectionRegistration) o;
            return element.equals(that.element);
        }

        @Override
        public int hashCode() {
            return element.hashCode();
        }
    }

    public static class ReachabilityNotificationCallback extends CustomReason {
        public final Consumer<org.graalvm.nativeimage.hosted.Feature.DuringAnalysisAccess> callback;

        public ReachabilityNotificationCallback(Consumer<org.graalvm.nativeimage.hosted.Feature.DuringAnalysisAccess> callback) {
            this.callback = callback;
        }

        @Override
        public String toString() {
            return "Reachability callback: " + callback;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ReachabilityNotificationCallback that = (ReachabilityNotificationCallback) o;
            return callback.equals(that.callback);
        }

        @Override
        public int hashCode() {
            return Objects.hash(callback);
        }
    }

    public static class BuildTimeClassInitialization extends Reason {
        public final Class<?> clazz;

        public BuildTimeClassInitialization(Class<?> clazz) {
            this.clazz = clazz;
        }

        @Override
        public String toString() {
            return clazz.getTypeName() + ".<clinit>() [BUILD TIME]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BuildTimeClassInitialization that = (BuildTimeClassInitialization) o;
            return clazz.equals(that.clazz);
        }

        @Override
        public int hashCode() {
            return Objects.hash(clazz);
        }
    }

    public static class HeapObjectClass extends Reason {
        public final Class<?> clazz;

        public HeapObjectClass(Class<?> clazz) {
            this.clazz = clazz;
        }

        @Override
        public String toString() {
            return "Class in Heap: " + clazz.getTypeName();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            HeapObjectClass that = (HeapObjectClass) o;
            return clazz.equals(that.clazz);
        }

        @Override
        public int hashCode() {
            return clazz.hashCode();
        }
    }

    public static class HeapObjectDynamicHub extends Reason {
        public final Class<?> forClass;


        public HeapObjectDynamicHub(Class<?> forClass) {
            this.forClass = forClass;
        }

        @Override
        public String toString() {
            return "DynamicHub in Heap: " + forClass.getTypeName();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            HeapObjectDynamicHub that = (HeapObjectDynamicHub) o;
            return forClass.equals(that.forClass);
        }

        @Override
        public int hashCode() {
            return forClass.hashCode();
        }
    }

    public static class UnknownHeapObject extends Reason {
        public final Class<?> heapObjectType;

        public UnknownHeapObject(Class<?> heapObjectType) {
            this.heapObjectType = heapObjectType;
        }

        @Override
        public boolean root() {
            return true;
        }

        @Override
        public String toString() {
            return "Unknown Heap Object: " + heapObjectType.getTypeName();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            UnknownHeapObject that = (UnknownHeapObject) o;
            return heapObjectType.equals(that.heapObjectType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(heapObjectType);
        }
    }

    // Can be used in Rerooting to indicate that registrations simply should be ignored
    public static class Ignored extends Reason {
        public static final Ignored Instance = new Ignored();

        private Ignored() {}

        @Override
        public boolean unused() {
            return true;
        }

        @Override
        public String toString() {
            return "Ignored dummy node that never happens";
        }
    }

    public static class Feature extends Reason {
        public final org.graalvm.nativeimage.hosted.Feature f;

        public Feature(org.graalvm.nativeimage.hosted.Feature f) {
            this.f = f;
        }

        @Override
        public String toString() {
            String str = "Feature: " + f.getClass().getTypeName();
            String description = f.getDescription();
            if(description != null)
                str += " - " + description;
            return str;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Feature feature = (Feature) o;

            return f.equals(feature.f);
        }

        @Override
        public int hashCode() {
            return f.hashCode();
        }
    }

    public static class AutomaticFeatureRegistration extends Reason {
        public static final AutomaticFeatureRegistration Instance = new AutomaticFeatureRegistration();

        private AutomaticFeatureRegistration() {}

        @Override
        public String toString() {
            return "Automatic Feature Registration";
        }

        @Override
        public boolean root() {
            return true;
        }
    }

    public static class UserEnabledFeatureRegistration extends Reason {
        public static final UserEnabledFeatureRegistration Instance = new UserEnabledFeatureRegistration();

        private UserEnabledFeatureRegistration() {}

        @Override
        public String toString() {
            return "User-Requested Feature Registration";
        }

        @Override
        public boolean root() {
            return true;
        }
    }
}

