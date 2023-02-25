package com.oracle.graal.pointsto.reports;

import com.oracle.graal.pointsto.ObjectScanner;
import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.flow.AbstractVirtualInvokeTypeFlow;
import com.oracle.graal.pointsto.flow.MethodTypeFlow;
import com.oracle.graal.pointsto.flow.TypeFlow;
import com.oracle.graal.pointsto.meta.AnalysisElement;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisMetaAccess;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.reports.causality.EmptyImpl;
import com.oracle.graal.pointsto.reports.causality.Impl;
import com.oracle.graal.pointsto.reports.causality.Graph;
import com.oracle.graal.pointsto.typestate.TypeState;
import jdk.vm.ci.meta.JavaConstant;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
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
        HeapAssignmentTracing.getInstance().dispose();
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

    public abstract Reason getReasonForHeapObject(PointsToAnalysis bb, JavaConstant heapObject, ObjectScanner.ScanReason reason);

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

        public Class<?> getContainingType() {
            return null;
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
            return !element.isReachable();
        }

        @Override
        public Class<?> getContainingType() {
            return element.getDeclaringClass().getJavaClass();
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

        @Override
        public Class<?> getContainingType() {
            return element.getJavaClass();
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
            return type.toJavaName() + " [Instantiated]";
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

        @Override
        public Class<?> getContainingType() {
            return type.getJavaClass();
        }
    }

    public static abstract class ReflectionObjectRegistration extends Reason {
        public final Object element;

        public ReflectionObjectRegistration(Executable method) {
            this.element = method;
        }

        public ReflectionObjectRegistration(Field field) {
            this.element = field;
        }

        public ReflectionObjectRegistration(Class<?> clazz) {
            this.element = clazz;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ReflectionObjectRegistration that = (ReflectionObjectRegistration) o;
            return element.equals(that.element);
        }

        @Override
        public int hashCode() {
            return getClass().hashCode() ^ element.hashCode();
        }

        @Override
        public Class<?> getContainingType() {
            if(element instanceof Class<?>) {
                return (Class<?>) element;
            } else if (element instanceof Executable) {
                return ((Executable) element).getDeclaringClass();
            } else {
                return ((Field) element).getDeclaringClass();
            }
        }
    }

    public static class JNIRegistration extends ReflectionObjectRegistration {
        public JNIRegistration(Executable method) {
            super(method);
        }

        public JNIRegistration(Field field) {
            super(field);
        }

        public JNIRegistration(Class<?> clazz) {
            super(clazz);
        }

        @Override
        public String toString() {
            return reflectionObjectToString(element) + " [JNI Registration]";
        }
    }

    public static class ReflectionRegistration extends ReflectionObjectRegistration {
        public ReflectionRegistration(Executable method) {
            super(method);
        }

        public ReflectionRegistration(Field field) {
            super(field);
        }

        public ReflectionRegistration(Class<?> clazz) {
            super(clazz);
        }

        @Override
        public String toString() {
            return reflectionObjectToString(element) + " [Reflection Registration]";
        }
    }

    public static class ReachabilityNotificationCallback extends Reason {
        public final Consumer<org.graalvm.nativeimage.hosted.Feature.DuringAnalysisAccess> callback;

        public ReachabilityNotificationCallback(Consumer<org.graalvm.nativeimage.hosted.Feature.DuringAnalysisAccess> callback) {
            this.callback = callback;
        }

        @Override
        public String toString() {
            return callback + " [Reachability Callback]";
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
            return clazz.getTypeName() + ".<clinit>() [Build-Time]";
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

        @Override
        public Class<?> getContainingType() {
            return clazz;
        }
    }

    public static class HeapObjectClass extends Reason {
        public final Class<?> clazz;

        public HeapObjectClass(Class<?> clazz) {
            this.clazz = clazz;
        }

        @Override
        public String toString() {
            return clazz.getTypeName() + " [Class in Heap]";
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

        @Override
        public Class<?> getContainingType() {
            return clazz;
        }
    }

    public static class HeapObjectDynamicHub extends Reason {
        public final Class<?> forClass;


        public HeapObjectDynamicHub(Class<?> forClass) {
            this.forClass = forClass;
        }

        @Override
        public String toString() {
            return forClass.getTypeName() + " [DynamicHub in Heap]";
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

        @Override
        public Class<?> getContainingType() {
            return forClass;
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
            return heapObjectType.getTypeName() + " [Unknown Heap Object]";
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

        @Override
        public Class<?> getContainingType() {
            return heapObjectType;
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
            throw new RuntimeException("[Ignored dummy node that never happens]");
        }
    }

    public static class Feature extends Reason {
        public final org.graalvm.nativeimage.hosted.Feature f;

        public Feature(org.graalvm.nativeimage.hosted.Feature f) {
            this.f = f;
        }

        @Override
        public String toString() {
            String str = f.getClass().getTypeName();
            String description = f.getDescription();
            if(description != null)
                str += " [Feature: " + description + "]";
            else
                str += " [Feature]";
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

        @Override
        public Class<?> getContainingType() {
            return f.getClass();
        }
    }

    public static class AutomaticFeatureRegistration extends Reason {
        public static final AutomaticFeatureRegistration Instance = new AutomaticFeatureRegistration();

        private AutomaticFeatureRegistration() {}

        @Override
        public String toString() {
            return "[Automatic Feature Registration]";
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
            return "[User-Requested Feature Registration]";
        }

        @Override
        public boolean root() {
            return true;
        }
    }

    public static class InitialRegistration extends Reason {
        public static InitialRegistration Instance = new InitialRegistration();

        private InitialRegistration() { }

        @Override
        public String toString() {
            return "[Initial Registrations]";
        }

        @Override
        public boolean root() {
            return true;
        }
    }

    public static class ConfigurationFile extends Reason {
        public final URI uri;

        public ConfigurationFile(URI uri) {
            this.uri = uri;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ConfigurationFile that = (ConfigurationFile) o;
            return uri.equals(that.uri);
        }

        @Override
        public int hashCode() {
            return uri.hashCode();
        }

        @Override
        public String toString() {
            String path;

            if(uri.getPath() != null)
                path = uri.getPath();
            else
            {
                path = uri.toString();
                if(path.startsWith("jar:file:"))
                    path = path.substring(9);
            }

            return path + " [Configuration File]";
        }

        @Override
        public boolean root() {
            return true;
        }
    }
}

