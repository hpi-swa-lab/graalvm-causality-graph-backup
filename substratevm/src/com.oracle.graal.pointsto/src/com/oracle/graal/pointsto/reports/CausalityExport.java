package com.oracle.graal.pointsto.reports;

import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.flow.AbstractVirtualInvokeTypeFlow;
import com.oracle.graal.pointsto.flow.MethodTypeFlow;
import com.oracle.graal.pointsto.flow.TypeFlow;
import com.oracle.graal.pointsto.meta.AnalysisElement;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.reports.causality.EmptyImpl;
import com.oracle.graal.pointsto.reports.causality.Impl;
import com.oracle.graal.pointsto.reports.causality.Graph;
import com.oracle.graal.pointsto.typestate.TypeState;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaMethod;
import org.graalvm.nativeimage.hosted.Feature;

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

    public abstract void addTypeFlowFromHeap(PointsToAnalysis analysis, Class<?> creason, TypeFlow<?> fieldTypeFlow, AnalysisType flowingType);

    public abstract void addDirectInvoke(AnalysisMethod caller, AnalysisMethod callee);

    public abstract void addVirtualInvoke(PointsToAnalysis bb, AbstractVirtualInvokeTypeFlow invocation, AnalysisMethod concreteTargetMethod, TypeState concreteTargetMethodCallingTypes);

    public abstract void registerMethodFlow(MethodTypeFlow method);

    public abstract void registerVirtualInvokeTypeFlow(AbstractVirtualInvokeTypeFlow invocation);

    public abstract void registerTypeReachableRoot(AnalysisType type, boolean instantiated);

    public abstract void registerTypeReachableRoot(Class<?> type);

    public abstract void registerTypeReachableThroughHeap(AnalysisType type, JavaConstant object, boolean instantiated);

    public abstract void registerTypeReachableByMethod(AnalysisType type, JavaMethod m, boolean instantiated);

    public abstract void registerTypeInstantiated(PointsToAnalysis bb, TypeFlow<?> cause, AnalysisType type);

    public abstract void registerReachabilityNotification(AnalysisElement e, Consumer<Feature.DuringAnalysisAccess> callback);

    public final ReRootingToken accountRootRegistrationsTo(CustomReason reason) {
        beginAccountingRootRegistrationsTo(reason);
        return new ReRootingToken(reason);
    }

    // May be unrooted due to an ongoing accountRootRegistrationsTo(...)
    public abstract void registerReasonRoot(CustomReason reason);

    protected abstract void beginAccountingRootRegistrationsTo(CustomReason reason);

    protected abstract void endAccountingRootRegistrationsTo(CustomReason reason);

    // Allows the simple usage of accountRootRegistrationsTo() in a try-with-resources statement
    public class ReRootingToken implements AutoCloseable {
        private final CustomReason reason;

        ReRootingToken(CustomReason reason) {
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
            ReachableReason that = (ReachableReason) o;
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
}

