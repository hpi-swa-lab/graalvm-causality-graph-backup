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
import org.graalvm.nativeimage.hosted.Feature;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

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
        Graph g = data.createCausalityGraph(bb);
        g.export(bb);
    }


    // --- Registration ---
    public abstract void addTypeFlow(TypeFlow<?> flow);

    public abstract void addTypeFlowEdge(TypeFlow<?> from, TypeFlow<?> to);

    public abstract void setSaturationHappening(boolean currentlySaturating);

    public abstract void registerTypesFlowing(PointsToAnalysis bb, Reason reason, TypeFlow<?> destination, TypeState types);

    public abstract void addDirectInvoke(AnalysisMethod caller, AnalysisMethod callee);

    public abstract void register(Reason reason, Reason consequence);

    public abstract void addVirtualInvoke(PointsToAnalysis bb, AbstractVirtualInvokeTypeFlow invocation, AnalysisMethod concreteTargetMethod, TypeState concreteTargetMethodCallingTypes);

    public abstract void registerMethodFlow(MethodTypeFlow method);

    public abstract void registerVirtualInvokeTypeFlow(AbstractVirtualInvokeTypeFlow invocation);

    public abstract void registerTypeReachable(Reason reason, AnalysisType type, boolean instantiated);

    public abstract Reason getReasonForHeapObject(PointsToAnalysis bb, JavaConstant heapObject);

    public abstract Reason getReasonForHeapFieldAssignment(PointsToAnalysis analysis, JavaConstant receiver, AnalysisField field, JavaConstant value);

    public abstract Reason getReasonForHeapArrayAssignment(PointsToAnalysis analysis, JavaConstant array, int elementIndex, JavaConstant value);

    public abstract void registerTypeInstantiated(PointsToAnalysis bb, TypeFlow<?> cause, AnalysisType type);

    public abstract void registerReachabilityNotification(AnalysisElement e, Consumer<Feature.DuringAnalysisAccess> callback);

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

    public static abstract class Reason {
        public boolean unused() {
            return false;
        }
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
            return "JNI registration of " + element.toString();
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
            return "Reflection registration of " + element.toString();
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
        public final Consumer<Feature.DuringAnalysisAccess> callback;

        public ReachabilityNotificationCallback(Consumer<Feature.DuringAnalysisAccess> callback) {
            this.callback = callback;
        }

        @Override
        public String toString() {
            return "Reachability callback " + callback;
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
            return Objects.hash(forClass);
        }
    }

    public static class UnknownHeapObject extends Reason {
        public final Class<?> heapObjectType;


        public UnknownHeapObject(Class<?> heapObjectType) {
            this.heapObjectType = heapObjectType;
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
}

