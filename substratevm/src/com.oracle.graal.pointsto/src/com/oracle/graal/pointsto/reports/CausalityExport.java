package com.oracle.graal.pointsto.reports;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.ObjectScanner;
import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.constraints.UnsupportedFeatureException;
import com.oracle.graal.pointsto.flow.AbstractVirtualInvokeTypeFlow;
import com.oracle.graal.pointsto.flow.TypeFlow;
import com.oracle.graal.pointsto.meta.AnalysisElement;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisMetaAccess;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.reports.causality.Impl;
import com.oracle.graal.pointsto.reports.causality.Graph;
import com.oracle.graal.pointsto.reports.causality.TypeflowImpl;
import com.oracle.graal.pointsto.typestate.TypeState;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaField;
import jdk.vm.ci.meta.JavaMethod;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.MetaUtil;
import jdk.vm.ci.meta.Signature;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.ZipOutputStream;

public class CausalityExport {
    protected CausalityExport() {
    }

    private static final CausalityExport dummyInstance = new CausalityExport();
    private static ThreadLocal<Impl> instances;
    private static List<Impl> instancesOfAllThreads;
    private static boolean collectTypeflowInformation;

    // Starts collection of Causality Data
    public static synchronized void activate(boolean collectTypeflowInformation) {
        CausalityExport.collectTypeflowInformation = collectTypeflowInformation;
        instances = ThreadLocal.withInitial(CausalityExport::createInstance);
        instancesOfAllThreads = new ArrayList<>();
    }

    private static synchronized Impl createInstance() {
        Impl instance = collectTypeflowInformation ? new TypeflowImpl() : new Impl();
        instancesOfAllThreads.add(instance);
        return instance;
    }

    public static CausalityExport get() {
        return instances != null ? instances.get() : dummyInstance;
    }

    public static synchronized void dump(PointsToAnalysis bb, ZipOutputStream zip, boolean exportTypeflowNames) throws java.io.IOException {
        Impl data = collectTypeflowInformation ? new TypeflowImpl((Iterable<TypeflowImpl>)(Iterable<? extends Impl>) instancesOfAllThreads, bb) : new Impl(instancesOfAllThreads, bb);
        // Let GC collect intermediate data structures
        instances = null;
        instancesOfAllThreads = null;
        Graph g = data.createCausalityGraph(bb);
        g.export(bb, zip, exportTypeflowNames);
    }


    // --- Registration ---

    public void addVirtualInvokeTypeFlow(AbstractVirtualInvokeTypeFlow invocation) {}

    public void registerVirtualInvocation(PointsToAnalysis bb, AbstractVirtualInvokeTypeFlow invocation, AnalysisMethod concreteTargetMethod, AnalysisType concreteTargetType) {}

    public void registerTypeFlowEdge(TypeFlow<?> from, TypeFlow<?> to) {}

    public final SaturationHappeningToken setSaturationHappening()
    {
        beginSaturationHappening();
        return new SaturationHappeningToken();
    }

    protected void beginSaturationHappening() {}

    protected void endSaturationHappening() {}

    public class SaturationHappeningToken implements AutoCloseable {
        @Override
        public void close() {
            endSaturationHappening();
        }

        SaturationHappeningToken() { }
    }

    public void registerEvent(Event event) {}

    public void registerEdge(Event cause, Event consequence) {}

    public void registerConjunctiveEdge(Event cause1, Event cause2, Event consequence) {}

    public Event getHeapObjectCreator(Object heapObject, ObjectScanner.ScanReason reason) {
        return null;
    }

    public Event getHeapObjectCreator(BigBang bb, JavaConstant heapObject, ObjectScanner.ScanReason reason) {
        return null;
    }

    public Event getHeapFieldAssigner(BigBang analysis, JavaConstant receiver, AnalysisField field, JavaConstant value) {
        return null;
    }

    public Event getHeapArrayAssigner(BigBang analysis, JavaConstant array, int elementIndex, JavaConstant value) {
        return null;
    }

    public void registerTypesEntering(PointsToAnalysis bb, Event cause, TypeFlow<?> destination, TypeState types) {}

    public enum HeapTracing {
        None,
        Allocations,
        Full
    }

    public final CauseToken setCause(Event event, HeapTracing level) {
        var token = new CauseToken(event, level);
        beginCauseRegion(token);
        return token;
    }

    public final CauseToken setCause(Event event) {
        return setCause(event, HeapTracing.None);
    }

    protected void beginCauseRegion(CauseToken token) {}

    protected void endCauseRegion(CauseToken token) {}

    // Allows the simple usage of accountRootRegistrationsTo() in a try-with-resources statement
    public class CauseToken implements AutoCloseable {
        public final Event event;
        public final HeapTracing level;

        CauseToken(Event event, HeapTracing level) {
            this.event = event;
            this.level = level;
        }

        @Override
        public void close() {
            endCauseRegion(this);
        }
    }

    public Event getCause() {
        return null;
    }




    public static abstract class Event {
        public boolean unused() {
            return false;
        }

        public boolean root() { return false; }

        public String toString(AnalysisMetaAccess metaAccess) {
            return this.toString();
        }
    }

    public static abstract class ReachableEvent<T extends AnalysisElement> extends Event {
        public final T element;

        public ReachableEvent(T element) {
            this.element = element;
        }

        public static ReachableEvent<?> create(AnalysisElement e) {
            if(e instanceof AnalysisMethod)
                return new MethodReachable((AnalysisMethod) e);
            if(e instanceof AnalysisType)
                return new TypeReachable((AnalysisType) e);
            throw new IllegalArgumentException();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ReachableEvent<?> that = (ReachableEvent<?>) o;
            return element.equals(that.element);
        }

        @Override
        public int hashCode() {
            return getClass().hashCode() ^ element.hashCode();
        }
    }

    public static final class MethodReachable extends ReachableEvent<AnalysisMethod> {
        public MethodReachable(AnalysisMethod method) {
            super(method);
        }

        @Override
        public String toString() {
            return stableMethodName(element);
        }

        @Override
        public boolean unused() {
            return !element.isReachable();
        }
    }

    public static final class MethodCode extends Event {
        public final AnalysisMethod method;

        public MethodCode(AnalysisMethod method) {
            this.method = method;
        }

        @Override
        public String toString() {
            return stableMethodName(method) + " [Impl]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MethodCode that = (MethodCode) o;
            return method.equals(that.method);
        }

        @Override
        public boolean unused() {
            return !method.isReachable();
        }

        @Override
        public int hashCode() {
            return getClass().hashCode() ^ method.hashCode();
        }
    }

    public static final class VirtualMethodInvoked extends Event {
        public final AnalysisMethod method;

        public VirtualMethodInvoked(AnalysisMethod method) {
            this.method = method;
        }

        @Override
        public String toString() {
            return stableMethodName(method) + " [Virtual Invoke]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            VirtualMethodInvoked that = (VirtualMethodInvoked) o;
            return method.equals(that.method);
        }

        @Override
        public int hashCode() {
            return getClass().hashCode() ^ method.hashCode();
        }
    }

    public static final class MethodSnippet extends Event {
        public final AnalysisMethod method;

        public MethodSnippet(AnalysisMethod method) {
            this.method = method;
        }

        @Override
        public String toString() {
            return stableMethodName(method) + " [Snippet]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MethodSnippet that = (MethodSnippet) o;
            return method.equals(that.method);
        }

        @Override
        public int hashCode() {
            return getClass().hashCode() ^ method.hashCode();
        }
    }

    public static final class TypeReachable extends ReachableEvent<AnalysisType> {
        public TypeReachable(AnalysisType type) {
            super(type);
        }

        @Override
        public String toString() {
            return stableTypeName(element);
        }

        @Override
        public boolean unused() {
            return !element.isReachable();
        }
    }

    public static final class TypeInstantiated extends Event {
        public final AnalysisType type;

        public TypeInstantiated(AnalysisType type) {
            this.type = type;
        }

        @Override
        public boolean unused() {
            return !type.isInstantiated();
        }

        @Override
        public String toString() {
            return stableTypeName(type) + " [Instantiated]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TypeInstantiated that = (TypeInstantiated) o;
            return type.equals(that.type);
        }

        @Override
        public int hashCode() {
            return getClass().hashCode() ^ type.hashCode();
        }
    }

    public static abstract class ReflectionObjectRegistration extends Event {
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

        protected abstract String getSuffix();

        @Override
        public String toString() {
            return reflectionObjectToString(element) + getSuffix();
        }

        @Override
        public String toString(AnalysisMetaAccess metaAccess) {
            return reflectionObjectToGraalLikeString(metaAccess, element) + getSuffix();
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
        protected String getSuffix() {
            return " [JNI Registration]";
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
        protected String getSuffix() {
            return " [Reflection Registration]";
        }
    }

    public static class ReachabilityNotificationCallback extends Event {
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
            return getClass().hashCode() ^ callback.hashCode();
        }
    }

    public static class BuildTimeClassInitialization extends Event {
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
            return getClass().hashCode() ^ clazz.hashCode();
        }

        private String getTypeName(AnalysisMetaAccess metaAccess) {
            return stableTypeName(metaAccess.getWrapped().lookupJavaType(clazz));
        }

        @Override
        public String toString(AnalysisMetaAccess metaAccess) {
            return getTypeName(metaAccess) + ".<clinit>() [Build-Time]";
        }
    }

    public static class HeapObjectClass extends Event {
        public final Class<?> clazz;

        public HeapObjectClass(Class<?> clazz) {
            this.clazz = clazz;
        }

        @Override
        public String toString() {
            return clazz.getTypeName() + " [Class-Object in Heap]";
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
            return getClass().hashCode() ^  clazz.hashCode();
        }
    }

    public static class HeapObjectDynamicHub extends Event {
        public final Class<?> forClass;


        public HeapObjectDynamicHub(Class<?> forClass) {
            this.forClass = forClass;
        }

        @Override
        public String toString() {
            return forClass.getTypeName() + " [DynamicHub-Object in Heap]";
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
            return getClass().hashCode() ^ forClass.hashCode();
        }
    }

    public static class UnknownHeapObject extends Event {
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
            return getClass().hashCode() ^ heapObjectType.hashCode();
        }

        @Override
        public String toString(AnalysisMetaAccess metaAccess) {
            return stableTypeName(metaAccess.lookupJavaType(heapObjectType)) + " [Unknown Heap Object]";
        }
    }

    public static class TypeInHeap extends Event {
        public final AnalysisType type;

        public TypeInHeap(AnalysisType type) {
            this.type = type;
        }

        @Override
        public String toString() {
            return stableTypeName(type) + " [Type In Heap]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TypeInHeap that = (TypeInHeap) o;
            return type.equals(that.type);
        }

        @Override
        public int hashCode() {
            return getClass().hashCode() ^ type.hashCode();
        }
    }

    public static final class ReflectionObjectInHeap extends ReflectionObjectRegistration {
        public ReflectionObjectInHeap(Executable method) {
            super(method);
        }

        public ReflectionObjectInHeap(Field field) {
            super(field);
        }

        public ReflectionObjectInHeap(Class<?> clazz) {
            super(clazz);
        }

        @Override
        protected String getSuffix() {
            return " [Reflection Object In Heap]";
        }
    }

    // Can be used in Rerooting to indicate that registrations simply should be ignored
    public static class Ignored extends Event {
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

    public static class Feature extends Event {
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
    }

    public static class AutomaticFeatureRegistration extends Event {
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

    public static class UserEnabledFeatureRegistration extends Event {
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

    public static class InitialRegistration extends Event {
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

    public static class ConfigurationFile extends Event {
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

    public static class RootMethodRegistration extends Event {
        public final AnalysisMethod method;

        public RootMethodRegistration(AnalysisMethod method) {
            this.method = method;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RootMethodRegistration that = (RootMethodRegistration) o;
            return method.equals(that.method);
        }

        @Override
        public int hashCode() {
            return getClass().hashCode() ^ method.hashCode();
        }

        @Override
        public String toString() {
            return stableMethodName(method) + " [Root Registration]";
        }
    }

    public static class ConfigurationCondition extends Event {
        public final String typeName;

        public ConfigurationCondition(String typeName) {
            this.typeName = typeName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ConfigurationCondition that = (ConfigurationCondition) o;
            return typeName.equals(that.typeName);
        }

        @Override
        public int hashCode() {
            return getClass().hashCode() ^ typeName.hashCode();
        }

        @Override
        public String toString() {
            return typeName + " [Configuration Condition]";
        }
    }

    public static class JniCallVariantWrapper extends Event {
        public final Signature signature;
        public final boolean virtual;

        public JniCallVariantWrapper(Signature signature, boolean virtual) {
            this.signature = signature;
            this.virtual = virtual;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            JniCallVariantWrapper that = (JniCallVariantWrapper) o;

            if (virtual != that.virtual) return false;
            return signature.equals(that.signature);
        }

        @Override
        public int hashCode() {
            int result = signature.hashCode();
            result = 31 * result + (virtual ? 1 : 0);
            return result;
        }

        @Override
        public String toString() {
            return signature + (virtual ? " [Virtual JNI Call Variant Wrapper]" : " [JNI Call Variant Wrapper]");
        }
    }

    public static class OverrideReachableNotificationCallback extends Event {
        public final BiConsumer<org.graalvm.nativeimage.hosted.Feature.DuringAnalysisAccess, Executable> callback;

        public OverrideReachableNotificationCallback(BiConsumer<org.graalvm.nativeimage.hosted.Feature.DuringAnalysisAccess, Executable> callback) {
            this.callback = callback;
        }

        @Override
        public String toString() {
            return callback + " [Method Override Reachable Callback]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OverrideReachableNotificationCallback that = (OverrideReachableNotificationCallback) o;
            return callback.equals(that.callback);
        }

        @Override
        public int hashCode() {
            return getClass().hashCode() ^ callback.hashCode();
        }
    }

    public static class OverrideReachableNotificationCallbackInvocation extends Event {
        public final BiConsumer<org.graalvm.nativeimage.hosted.Feature.DuringAnalysisAccess, Executable> callback;
        public final AnalysisMethod override;

        public OverrideReachableNotificationCallbackInvocation(BiConsumer<org.graalvm.nativeimage.hosted.Feature.DuringAnalysisAccess, Executable> callback, AnalysisMethod override) {
            this.callback = callback;
            this.override = override;
        }

        @Override
        public String toString() {
            return callback + " + " + stableMethodName(override) + " [Method Override Reachable Callback Invocation]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OverrideReachableNotificationCallbackInvocation that = (OverrideReachableNotificationCallbackInvocation) o;
            return callback.equals(that.callback) && override.equals(that.override);
        }

        @Override
        public int hashCode() {
            return getClass().hashCode() ^ callback.hashCode() ^ override.hashCode();
        }
    }

    public static class SubtypeReachableNotificationCallback extends Event {
        public final BiConsumer<org.graalvm.nativeimage.hosted.Feature.DuringAnalysisAccess, Class<?>> callback;

        public SubtypeReachableNotificationCallback(BiConsumer<org.graalvm.nativeimage.hosted.Feature.DuringAnalysisAccess, Class<?>> callback) {
            this.callback = callback;
        }

        @Override
        public String toString() {
            return callback + " [Subtype Reachable Callback]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            var that = (SubtypeReachableNotificationCallback) o;
            return callback.equals(that.callback);
        }

        @Override
        public int hashCode() {
            return getClass().hashCode() ^ callback.hashCode();
        }
    }

    public static class SubtypeReachableNotificationCallbackInvocation extends Event {
        public final BiConsumer<org.graalvm.nativeimage.hosted.Feature.DuringAnalysisAccess, Class<?>> callback;
        public final AnalysisType subtype;

        public SubtypeReachableNotificationCallbackInvocation(BiConsumer<org.graalvm.nativeimage.hosted.Feature.DuringAnalysisAccess, Class<?>> callback, AnalysisType subtype) {
            this.callback = callback;
            this.subtype = subtype;
        }

        @Override
        public String toString() {
            return callback + " + " + stableTypeName(subtype) + " [Subtype Reachable Callback Invocation]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            var that = (SubtypeReachableNotificationCallbackInvocation) o;
            return callback.equals(that.callback) && subtype.equals(that.subtype);
        }

        @Override
        public int hashCode() {
            return getClass().hashCode() ^ callback.hashCode() ^ subtype.hashCode();
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

    private static String reflectionObjectToGraalLikeString(AnalysisMetaAccess metaAccess, Object reflectionObject) {
        if(reflectionObject instanceof Class<?> c) {
            return stableTypeName(metaAccess.lookupJavaType(c));
        } else if(reflectionObject instanceof Executable e) {
            return stableMethodName(metaAccess.lookupJavaMethod(e));
        } else {
            return stableFieldName(metaAccess.lookupJavaField((Field) reflectionObject));
        }
    }

    // Hotfix until c322e16d4b406f9e6b54a6188d19ef0c3a8b4535 gets merged

    public static String stableTypeName(JavaType t) {
        return MetaUtil.internalNameToJava(t.getName(), true, false);
    }

    public static String stableFieldName(JavaField f) {
        return stableTypeName(f.getDeclaringClass()) + '.' + f.getName();
    }

    public static String stableMethodName(JavaMethod m) {
        StringBuilder sb = new StringBuilder();
        sb.append(stableTypeName(m.getDeclaringClass()));
        sb.append('.');
        sb.append(m.getName());
        sb.append('(');

        Signature sig = m.getSignature();
        for(int i = 0; i < sig.getParameterCount(false); i++) {
            if (i != 0) {
                sb.append(", ");
            }
            sb.append(stableTypeName(sig.getParameterType(i, null)));
        }

        sb.append(')');
        sb.append(':');
        sb.append(stableTypeName(sig.getReturnType(null)));

        return sb.toString();
    }
}

