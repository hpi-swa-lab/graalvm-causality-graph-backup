package com.oracle.graal.pointsto.reports;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.flow.AccessFieldTypeFlow;
import com.oracle.graal.pointsto.flow.AllInstantiatedTypeFlow;
import com.oracle.graal.pointsto.flow.ArrayElementsTypeFlow;
import com.oracle.graal.pointsto.flow.ConstantTypeFlow;
import com.oracle.graal.pointsto.flow.FieldTypeFlow;
import com.oracle.graal.pointsto.flow.FilterTypeFlow;
import com.oracle.graal.pointsto.flow.FormalParamTypeFlow;
import com.oracle.graal.pointsto.flow.FormalReceiverTypeFlow;
import com.oracle.graal.pointsto.flow.MethodTypeFlow;
import com.oracle.graal.pointsto.flow.NewInstanceTypeFlow;
import com.oracle.graal.pointsto.flow.OffsetLoadTypeFlow;
import com.oracle.graal.pointsto.flow.OffsetStoreTypeFlow;
import com.oracle.graal.pointsto.flow.SourceTypeFlow;
import com.oracle.graal.pointsto.flow.TypeFlow;
import com.oracle.graal.pointsto.meta.AnalysisElement;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.typestate.TypeState;
import jdk.vm.ci.code.BytecodePosition;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaMethod;
import org.graalvm.collections.Pair;
import org.graalvm.nativeimage.hosted.Feature;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CausalityExport {

    private static class Impl extends CausalityExport {
        // All typeflows ever constructed
        private final HashSet<TypeFlow<?>> typeflows = new HashSet<>();
        private final HashSet<Pair<TypeFlow<?>, TypeFlow<?>>> interflows = new HashSet<>();
        private final HashSet<Pair<AnalysisMethod, AnalysisMethod>> direct_invokes = new HashSet<>();
        private final HashMap<Pair<TypeFlow<?>, AnalysisMethod>, TypeState> virtual_invokes = new HashMap<>();
        private final HashMap<TypeFlow<?>, AnalysisMethod> typeflowGateMethods = new HashMap<>();
        private final HashMap<AnalysisElement, Integer> rootCount = new HashMap<>();
        private final HashSet<Class<?>> unresolvedRootTypes = new HashSet<>();
        // Boolean stores whether the type is also instantiated
        private final HashMap<Pair<AnalysisMethod, AnalysisType>, Boolean> methodsReachingTypes = new HashMap<>();
        private final Map<Consumer<Feature.DuringAnalysisAccess>, List<AnalysisElement>> callbackReasons = new HashMap<>();
        private final HashSet<Pair<Consumer<Feature.DuringAnalysisAccess>, Object>> reachableThroughFeatureCallback = new HashSet<>();

        private Consumer<Feature.DuringAnalysisAccess> runningNotification = null;

        public Impl() {}

        private static <K, V> void mergeMap(Map<K, V> dst, Map<K, V> src, BiFunction<V, V, V> merger) {
            src.forEach((k, v) -> dst.merge(k, v, merger));
        }

        private static <K> void mergeTypeFlowMap(Map<K, TypeState> dst, Map<K, TypeState> src, PointsToAnalysis bb) {
            mergeMap(dst, src, (v1, v2) -> TypeState.forUnion(bb, v1, v2));
        }

        public Impl(List<Impl> instances, PointsToAnalysis bb) {
            for(Impl i : instances)
            {
                typeflows.addAll(i.typeflows);
                interflows.addAll(i.interflows);
                direct_invokes.addAll(i.direct_invokes);
                mergeTypeFlowMap(virtual_invokes, i.virtual_invokes, bb);
                typeflowGateMethods.putAll(i.typeflowGateMethods);
                i.rootCount.forEach((k, v) -> rootCount.merge(k, v, Integer::sum));
                unresolvedRootTypes.addAll(i.unresolvedRootTypes);
                mergeMap(methodsReachingTypes, i.methodsReachingTypes, Boolean::logicalOr);
                mergeMap(callbackReasons, i.callbackReasons, (l1, l2) -> Stream.concat(l1.stream(), l2.stream()).collect(Collectors.toList()));
                reachableThroughFeatureCallback.addAll(i.reachableThroughFeatureCallback);
            }
        }

        public void addTypeFlow(TypeFlow<?> flow) {
            typeflows.add(flow);
        }

        public void addTypeFlowEdge(TypeFlow<?> from, TypeFlow<?> to) {
            if(from == to)
                return;

            interflows.add(Pair.create(from, to));
        }

        public void addTypeFlowFromHeap(PointsToAnalysis analysis, Class<?> creason, TypeFlow<?> fieldTypeFlow, AnalysisType flowingType) {

            // Evil hack, but works for now...
            // TODO: Make the logical intermediate flow be contained in the <clinit>()-Method of creason
            FilterTypeFlow intermediate = new FilterTypeFlow((BytecodePosition) null, flowingType, true, true, false);
            addTypeFlowEdge(null, intermediate);
            addTypeFlowEdge(intermediate, fieldTypeFlow);
        }

        public void addDirectInvoke(AnalysisMethod caller, AnalysisMethod callee) {
            /*
            if (caller == null) {
                String qualifiedName = callee.getDeclaringClass().toJavaName();
                if(qualifiedName.startsWith("com.oracle.svm.core.") && qualifiedName.endsWith("Holder")) {
                    //System.err.println("Ignored Method Entry point: " + callee.getQualifiedName());
                    return;
                }
            }
            */

            if (caller == null) {
                rootCount.compute(callee, (id, count) -> (count == null ? 0 : count) + 1);
            }

            boolean alreadyExisting = !direct_invokes.add(Pair.create(caller, callee));
            assert !alreadyExisting : "Redundant adding of direct invoke";
        }

        public void addVirtualInvoke(PointsToAnalysis bb, TypeFlow<?> actualReceiver, AnalysisMethod concreteTargetMethod, TypeState concreteTargetMethodCallingTypes) {
            Pair<TypeFlow<?>, AnalysisMethod> e = Pair.create(actualReceiver, concreteTargetMethod);
            virtual_invokes.compute(e, (edge, state) -> state == null ? concreteTargetMethodCallingTypes : TypeState.forUnion(bb, state, concreteTargetMethodCallingTypes));
        }

        public void registerMethodFlow(MethodTypeFlow method) {
            for (TypeFlow<?> flow : method.getMethodFlowsGraph().flows()) {
                if (method.getMethod() == null)
                    throw new RuntimeException("Null method registered");
                if (flow.method() != null)
                    typeflowGateMethods.put(flow, method.getMethod());
            }
        }

        public void registerTypeReachableRoot(AnalysisType type, boolean instantiated)
        {
            methodsReachingTypes.compute(Pair.create(null, type), (t, wasInstantiated) -> wasInstantiated == null ? instantiated : wasInstantiated | instantiated);
        }

        public void registerTypeReachableRoot(Class<?> type)
        {
            unresolvedRootTypes.add(type);
        }

        public void registerTypeReachableThroughHeap(AnalysisType type, JavaConstant object, boolean instantiated)
        {
            if(type.getName().equals("Lorg/apache/log4j/helpers/NullEnumeration;"))
                return;

            methodsReachingTypes.compute(Pair.create(null, type), (t, wasInstantiated) -> wasInstantiated == null ? instantiated : wasInstantiated | instantiated); // TODO: Make build-time clinit accountable
        }

        public void registerTypeReachableByMethod(AnalysisType type, JavaMethod m, boolean instantiated)
        {
            methodsReachingTypes.compute(Pair.create((AnalysisMethod) m, type), (t, wasInstantiated) -> wasInstantiated == null ? instantiated : wasInstantiated | instantiated);
        }

        public void registerTypeInstantiated(PointsToAnalysis bb, TypeFlow<?> cause, AnalysisType type) {
            TypeState typeState = TypeState.forExactType(bb, type, true);

            type.forAllSuperTypes(t -> {
                addTypeFlowEdge(cause, t.instantiatedTypes);
                addTypeFlowEdge(cause, t.instantiatedTypesNonNull);
            });
        }

        static boolean indirectlyImplementsFeature(Class<?> clazz) {
            if (clazz.equals(Feature.class))
                return true;

            for (Class<?> interfaces : clazz.getInterfaces())
                if (indirectlyImplementsFeature(interfaces))
                    return true;

            return false;
        }

        public void registerReachabilityNotification(AnalysisElement e, Consumer<Feature.DuringAnalysisAccess> callback) {
            try {
                Class<?> callbackClass = Class.forName(callback.getClass().getName().split("\\$")[0]);

                if (!indirectlyImplementsFeature(callbackClass)) {
                    //System.err.println(callbackClass + " is not a Feature!");
                }

            } catch (ReflectiveOperationException ex) {
                System.err.println(ex);
            }

            callbackReasons.computeIfAbsent(callback, k -> new ArrayList<>()).add(e);
        }

        public void registerNotificationStart(Consumer<Feature.DuringAnalysisAccess> notification) {
            if (runningNotification != null)
                throw new RuntimeException("Notification already running!");

            runningNotification = notification;
        }

        public void registerNotificationEnd(Consumer<Feature.DuringAnalysisAccess> notification) {
            if (runningNotification != notification)
                throw new RuntimeException("Notification was not running!");

            runningNotification = null;
        }

        private void registerAnonymousRegistration(Object unresolvedElement)
        {
            Consumer<Feature.DuringAnalysisAccess> running = runningNotification;

            if (running != null) {
                reachableThroughFeatureCallback.add(Pair.create(running, unresolvedElement));
            }
        }

        public void registerAnonymousRegistration(Executable e) {
            registerAnonymousRegistration((Object)e);
        }

        public void registerAnonymousRegistration(Field f) {
            registerAnonymousRegistration((Object)f);
        }

        public void registerAnonymousRegistration(Class<?> c) {
            registerAnonymousRegistration((Object)c);
        }

        public Graph createCausalityGraph(PointsToAnalysis bb) {
            Stream<AnalysisType> lateResolvedTypes = unresolvedRootTypes.stream().map(bb.getMetaAccess()::optionalLookupJavaType).filter(Optional::isPresent).map(Optional::get);
            lateResolvedTypes.forEach(t -> methodsReachingTypes.putIfAbsent(Pair.create(null, t), false));
            unresolvedRootTypes.clear();

            {
                Set<AnalysisType> reachedAccordingToCausalityExport = methodsReachingTypes.keySet().stream().filter(p -> p.getLeft() == null || p.getLeft().isImplementationInvoked()).map(Pair::getRight).distinct().flatMap(t -> {
                    ArrayList<AnalysisType> superTypes = new ArrayList<>();
                    t.forAllSuperTypes(superTypes::add);
                    return superTypes.stream();
                }).collect(Collectors.toSet());

                List<AnalysisType> invokedByRoot = methodsReachingTypes.keySet().stream().filter(p -> p.getLeft() == null).map(Pair::getRight).distinct().collect(Collectors.toList());

                Set<AnalysisType> reached = bb.getUniverse().getTypes().stream().filter(AnalysisType::isReachable).collect(Collectors.toSet());

                if (!reached.containsAll(reachedAccordingToCausalityExport)) {
                    System.err.println("Types additionally reached according to CausalityExport!");
                }

                reached.removeAll(reachedAccordingToCausalityExport);

                List<AnalysisType> overlookedWithClassInitializer = reached.stream().filter(t -> t.getClassInitializer() != null).collect(Collectors.toList());

                for (AnalysisType t : overlookedWithClassInitializer) {
                    System.err.println("Type not reached according to CausalityExport: " + t.toJavaName());
                }


                for(AnalysisType t : reached) {
                    methodsReachingTypes.putIfAbsent(Pair.create(null, t), false); // Because we dont know...
                }
            }

            Graph g = new Graph();

            HashMap<AnalysisMethod, Graph.RealMethodNode> methodMapping = new HashMap<>();
            HashMap<TypeFlow<?>, Graph.RealFlowNode> flowMapping = new HashMap<>();
            HashMap<AnalysisType, Graph.ClassReachableNode> typeReachableMapping = new HashMap<>();

            {
                for (AnalysisMethod m : bb.getUniverse().getMethods()) {
                    if (m.isImplementationInvoked()) {
                        methodMapping.put(m, new Graph.RealMethodNode(m));
                    }
                }


                for (TypeFlow<?> f : typeflows) {
                    if (f.getState().typesCount() != 0) {
                        AnalysisMethod m = typeflowGateMethods.get(f);
                        Graph.MethodNode n = null;
                        if (m != null) {
                            n = methodMapping.get(m);
                            if (n == null)
                                continue;
                        }
                        flowMapping.put(f, new Graph.RealFlowNode(f, n));
                    }
                }

                for (AnalysisType t : bb.getUniverse().getTypes()) {
                    if (t.isReachable()) {
                        typeReachableMapping.put(t, new Graph.ClassReachableNode(t));
                    }
                }
            }


            for (Pair<AnalysisMethod, AnalysisMethod> e : direct_invokes) {
                if ((e.getLeft() != null && !methodMapping.containsKey(e.getLeft())) || !methodMapping.containsKey(e.getRight()))
                    continue;

                if (e.getLeft() == null && e.getRight().isClassInitializer())
                    continue; // Re-account class-initializers to type instantiation!

                g.directInvokes.add(new Graph.DirectCallEdge(e.getLeft() == null ? null : methodMapping.get(e.getLeft()), methodMapping.get(e.getRight())));
            }

            for (Map.Entry<Pair<TypeFlow<?>, AnalysisMethod>, TypeState> e : virtual_invokes.entrySet()) {
                if (!flowMapping.containsKey(e.getKey().getLeft()) || !methodMapping.containsKey(e.getKey().getRight()))
                    continue;

                g.virtualInvokes.put(new Graph.VirtualCallEdge(flowMapping.get(e.getKey().getLeft()), methodMapping.get(e.getKey().getRight())), e.getValue());
            }

            for (Pair<TypeFlow<?>, TypeFlow<?>> e : interflows) {
                if ((e.getLeft() != null && !flowMapping.containsKey(e.getLeft())) || !flowMapping.containsKey(e.getRight()))
                    continue;

                g.interflows.add(new Graph.FlowEdge(e.getLeft() == null ? null : flowMapping.get(e.getLeft()), flowMapping.get(e.getRight())));
            }

            for (Pair<AnalysisMethod, AnalysisType> e : methodsReachingTypes.keySet()) {
                AnalysisMethod m = e.getLeft();

                Graph.RealMethodNode mnode;
                if(m == null) {
                    mnode = null; // Intentionally null
                } else {
                    mnode = methodMapping.get(m);
                    if (mnode == null)
                        continue;
                }

                g.directInvokes.add(new Graph.DirectCallEdge(mnode, typeReachableMapping.get(e.getRight())));
            }

            for (Map.Entry<AnalysisType, Graph.ClassReachableNode> e : typeReachableMapping.entrySet()) {
                AnalysisMethod classInitializer = e.getKey().getClassInitializer();
                if (classInitializer != null && methodMapping.containsKey(classInitializer)) {
                    g.directInvokes.add(new Graph.DirectCallEdge(e.getValue(), methodMapping.get(classInitializer)));
                }
            }

            for(Map.Entry<Pair<AnalysisMethod, AnalysisType>, Boolean> kv : methodsReachingTypes.entrySet()) {
                if(kv.getValue()) {
                    AnalysisMethod m = kv.getKey().getLeft();

                    Graph.RealMethodNode mnode;
                    if(m == null) {
                        mnode = null; // Intentionally null
                    } else {
                        mnode = methodMapping.get(m);
                        if (mnode == null)
                            continue;
                    }

                    AnalysisType t = kv.getKey().getRight();
                    TypeState state = TypeState.forExactType(bb, t, false);

                    String debugStr = "Virtual Flow Node for reaching " + t.toJavaName();
                    if(m != null) {
                        debugStr += " in " + m.getQualifiedName();
                    }

                    Graph.FlowNode vfn = new Graph.FlowNode(debugStr, mnode, state);
                    g.interflows.add(new Graph.FlowEdge(null, vfn));

                    t.forAllSuperTypes(t1 -> {
                        g.interflows.add(new Graph.FlowEdge(vfn, flowMapping.get(t1.instantiatedTypes)));
                        g.interflows.add(new Graph.FlowEdge(vfn, flowMapping.get(t1.instantiatedTypesNonNull)));
                    });
                }
            }

            return g;
        }
    }


    private static final CausalityExport dummyInstance = new CausalityExport();
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
        instance.callbackReasons.get(null);
        return instance;
    }

    public static CausalityExport getInstance() {
        return instances != null ? instances.get() : dummyInstance;
    }


    // --- Registration ---
    public void addTypeFlow(TypeFlow<?> flow) {
    }

    public void addTypeFlowEdge(TypeFlow<?> from, TypeFlow<?> to) {
    }

    public void addTypeFlowFromHeap(PointsToAnalysis analysis, Class<?> creason, TypeFlow<?> fieldTypeFlow, AnalysisType flowingType) {
    }

    public void addDirectInvoke(AnalysisMethod caller, AnalysisMethod callee) {
    }

    public void addVirtualInvoke(PointsToAnalysis bb, TypeFlow<?> actualReceiver, AnalysisMethod concreteTargetMethod, TypeState concreteTargetMethodCallingTypes) {
    }

    public void registerMethodFlow(MethodTypeFlow method) {
    }

    public void registerTypeReachableRoot(AnalysisType type, boolean instantiated) {
    }

    public void registerTypeReachableRoot(Class<?> type) {
    }

    public void registerTypeReachableThroughHeap(AnalysisType type, JavaConstant object, boolean instantiated) {
    }

    public void registerTypeReachableByMethod(AnalysisType type, JavaMethod m, boolean instantiated) {
    }

    public void registerTypeInstantiated(PointsToAnalysis bb, TypeFlow<?> cause, AnalysisType type) {
    }

    public void registerReachabilityNotification(AnalysisElement e, Consumer<Feature.DuringAnalysisAccess> callback) {
    }

    public void registerNotificationStart(Consumer<Feature.DuringAnalysisAccess> notification) {
    }

    public void registerNotificationEnd(Consumer<Feature.DuringAnalysisAccess> notification) {
    }

    public void registerAnonymousRegistration(Executable e) {
    }

    public void registerAnonymousRegistration(Field f) {
    }

    public void registerAnonymousRegistration(Class<?> c) {
    }













    // --- Postprocessing ---

    private static class Graph {
        static abstract class Node implements Comparable<Node> {
            private final String toStringCached;

            public Node(String debugStr) {
                toStringCached = debugStr;
            }

            public final String toString() { return toStringCached; }

            @Override
            public int compareTo(Node o) {
                return toStringCached.compareTo(o.toStringCached);
            }
        }

        static abstract class MethodNode extends Node {
            public MethodNode(String debugStr) {
                super(debugStr);
            }
        }

        static class FeatureCallbackNode extends MethodNode {
            private final Consumer<Feature.DuringAnalysisAccess> callback;

            FeatureCallbackNode(Consumer<Feature.DuringAnalysisAccess> callback) {
                super(callback.getClass().toString());
                this.callback = callback;
            }
        }

        static class FlowNode extends Node {
            public final MethodNode containing;
            public final TypeState filter;

            FlowNode(String debugStr, MethodNode containing, TypeState filter) {
                super(debugStr);
                this.containing = containing;
                this.filter = filter;
            }
        }

        static class DirectCallEdge {
            public final MethodNode from, to;

            DirectCallEdge(MethodNode from, MethodNode to) {
                if(to == null)
                    throw new NullPointerException();

                this.from = from;
                this.to = to;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                DirectCallEdge that = (DirectCallEdge) o;
                return Objects.equals(from, that.from) && to.equals(that.to);
            }

            @Override
            public int hashCode() {
                return Objects.hash(from, to);
            }

            @Override
            public String toString() {
                return (from == null ? "" : from.toString()) + "->" + to.toString();
            }
        }

        static class VirtualCallEdge {
            public final FlowNode from;
            public final MethodNode to;

            VirtualCallEdge(FlowNode from, MethodNode to) {
                if(from == null)
                    throw new NullPointerException();
                if(to == null)
                    throw new NullPointerException();

                this.from = from;
                this.to = to;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                VirtualCallEdge that = (VirtualCallEdge) o;
                return from.equals(that.from) && to.equals(that.to);
            }

            @Override
            public int hashCode() {
                return Objects.hash(from, to);
            }
        }

        static class FlowEdge {
            public final FlowNode from, to;

            FlowEdge(FlowNode from, FlowNode to) {
                if(to == null)
                    throw new NullPointerException();

                this.from = from;
                this.to = to;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                FlowEdge flowEdge = (FlowEdge) o;
                return Objects.equals(from, flowEdge.from) && to.equals(flowEdge.to);
            }

            @Override
            public int hashCode() {
                return Objects.hash(from, to);
            }
        }

        static class RealMethodNode extends MethodNode {
            private final AnalysisMethod m;

            public RealMethodNode(AnalysisMethod m) {
                super(m.getQualifiedName());
                this.m = m;
            }
        }

        static class ClassReachableNode extends MethodNode {
            private final AnalysisType t;

            public ClassReachableNode(AnalysisType t) {
                super(t.toJavaName());
                this.t = t;
            }
        }

        static class RealFlowNode extends FlowNode {
            private final TypeFlow<?> f;

            static String customToString(TypeFlow<?> f) {
                String str = f.getClass().getSimpleName();

                if(f.method() != null) {
                    str += " in " + f.method().getQualifiedName();
                }

                String detail = null;

                if(f instanceof ArrayElementsTypeFlow) {
                    detail = ((ArrayElementsTypeFlow)f).getSourceObject().type().toJavaName();
                } else if(f instanceof FieldTypeFlow) {
                    detail = ((FieldTypeFlow)f).getSource().format("%H.%n");
                } else if(f instanceof AccessFieldTypeFlow) {
                    detail = ((AccessFieldTypeFlow)f).field().format("%H.%n");
                } else if(f instanceof NewInstanceTypeFlow || f instanceof ConstantTypeFlow || f instanceof SourceTypeFlow) {
                    detail = f.getDeclaredType().toJavaName();
                } else if(f instanceof OffsetLoadTypeFlow) {
                    detail = f.getDeclaredType().toJavaName();
                } else if(f instanceof OffsetStoreTypeFlow) {
                    detail = f.getDeclaredType().toJavaName();
                } else if(f instanceof AllInstantiatedTypeFlow) {
                    detail = f.getDeclaredType().toJavaName();
                } else if(f instanceof FormalParamTypeFlow && !(f instanceof FormalReceiverTypeFlow)) {
                    detail = Integer.toString(((FormalParamTypeFlow)f).position());
                }

                if(detail != null)
                    str += ": " + detail;

                return str;
            }

            public RealFlowNode(TypeFlow<?> f, MethodNode containing) {
                super(customToString(f), containing, f.getState());
                this.f = f;
            }
        }

        public HashSet<DirectCallEdge> directInvokes = new HashSet<>();
        public HashSet<FlowEdge> interflows = new HashSet<>();
        public HashMap<VirtualCallEdge, TypeState> virtualInvokes = new HashMap<>();

        private static <T> HashMap<T, Integer> inverse(T[] arr, int startIndex) {
            HashMap<T, Integer> idMap = new HashMap<>();

            int i = startIndex;
            for (T a : arr) {
                idMap.put(a, i);
                i++;
            }

            return idMap;
        }

        private static class SeenTypestates implements Iterable<TypeState>
        {
            private final ArrayList<TypeState> typestate_by_id = new ArrayList<>();
            private final HashMap<TypeState, Integer> typestate_to_id = new HashMap<>();

            private int assignId(TypeState s) {
                int size = typestate_by_id.size();
                typestate_by_id.add(s);
                return size;
            };

            public Integer getId(PointsToAnalysis bb, TypeState s) {
                return typestate_to_id.computeIfAbsent(s.forCanBeNull(bb, true), this::assignId);
            }

            @Override
            public Iterator<TypeState> iterator() {
                return typestate_by_id.iterator();
            }
        }

        public void export(PointsToAnalysis bb) throws java.io.IOException {
            Map<Integer, Integer> typeIdMap = makeDenseTypeIdMap(bb, bb.getAllInstantiatedTypeFlow().getState()::containsType);
            AnalysisType[] typesSorted = getRelevantTypes(bb, typeIdMap);

            HashSet<MethodNode> methods = new HashSet<>();
            HashSet<FlowNode> typeflows = new HashSet<>();

            for (DirectCallEdge e : directInvokes) {
                if (e.from != null)
                    methods.add(e.from);
                methods.add(e.to);
            }

            for (Map.Entry<VirtualCallEdge, TypeState> e : virtualInvokes.entrySet()) {
                methods.add(e.getKey().to);
                typeflows.add(e.getKey().from);
                if (e.getKey().from.containing != null)
                    methods.add(e.getKey().from.containing);
            }

            for (FlowEdge e : interflows) {
                if (e.from != null) {
                    typeflows.add(e.from);
                    if (e.from.containing != null)
                        methods.add(e.from.containing);
                }
                typeflows.add(e.to);
                if (e.to.containing != null)
                    methods.add(e.to.containing);
            }

            MethodNode[] methodsSorted = methods.stream().sorted().toArray(MethodNode[]::new);
            HashMap<MethodNode, Integer> methodIdMap = inverse(methodsSorted, 1);
            FlowNode[] flowsSorted = typeflows.stream().sorted().toArray(FlowNode[]::new);
            HashMap<FlowNode, Integer> flowIdMap = inverse(flowsSorted, 1);

            try (PrintStream w = new PrintStream(new FileOutputStream("types.txt"))) {
                for (AnalysisType type : typesSorted) {
                    w.println(type.toJavaName());
                }
            }

            try (PrintStream w = new PrintStream(new FileOutputStream("methods.txt"))) {
                for (MethodNode method : methodsSorted) {
                    w.println(method);
                }
            }

            try (PrintStream w = new PrintStream(new FileOutputStream("typeflows.txt"))) {
                for (FlowNode flow : flowsSorted) {
                    w.println(flow);
                }
            }

            try (FileOutputStream out = new FileOutputStream("direct_invokes.bin")) {
                FileChannel c = out.getChannel();

                ByteBuffer b = ByteBuffer.allocate(8);
                b.order(ByteOrder.LITTLE_ENDIAN);

                for (DirectCallEdge e : directInvokes) {
                    int src = e.from == null ? 0 : methodIdMap.get(e.from);
                    int dst = methodIdMap.get(e.to);

                    b.putInt(src);
                    b.putInt(dst);
                    b.flip();
                    c.write(b);
                    b.flip();
                }
            }

            try (PrintStream out = new PrintStream(new FileOutputStream("direct_invokes.txt"))) {
                for (DirectCallEdge e : directInvokes) {
                    out.println(e);
                }
            }

            SeenTypestates typestates = new SeenTypestates();

            try (FileOutputStream out = new FileOutputStream("interflows.bin")) {
                FileChannel c = out.getChannel();

                ByteBuffer b = ByteBuffer.allocate(8);
                b.order(ByteOrder.LITTLE_ENDIAN);

                for (FlowEdge e : interflows) {
                    b.putInt(e.from == null ? 0 : flowIdMap.get(e.from));
                    b.putInt(flowIdMap.get(e.to));
                    b.flip();
                    c.write(b);
                    b.flip();
                }
            }

            try (FileOutputStream out = new FileOutputStream("typeflow_filters.bin")) {
                FileChannel c = out.getChannel();

                ByteBuffer b = ByteBuffer.allocate(4);
                b.order(ByteOrder.LITTLE_ENDIAN);

                for (FlowNode flow : flowsSorted) {
                    int typestate_id = typestates.getId(bb, flow.filter);
                    b.putInt(typestate_id);
                    b.flip();
                    c.write(b);
                    b.flip();
                }
            }

            try (FileOutputStream out = new FileOutputStream("virtual_invokes.bin")) {
                FileChannel c = out.getChannel();

                ByteBuffer b = ByteBuffer.allocate(12);
                b.order(ByteOrder.LITTLE_ENDIAN);

                for (Map.Entry<VirtualCallEdge, TypeState> e : virtualInvokes.entrySet()) {
                    int typestate_id = typestates.getId(bb, e.getValue());

                    b.putInt(flowIdMap.get(e.getKey().from));
                    b.putInt(methodIdMap.get(e.getKey().to));
                    b.putInt(typestate_id);
                    b.flip();
                    c.write(b);
                    b.flip();
                }
            }

            try (FileOutputStream out = new FileOutputStream("typestates.bin")) {
                FileChannel c = out.getChannel();
                int bytesPerTypestate = (typesSorted.length + 7) / 8;

                ByteBuffer zero = ByteBuffer.allocate(bytesPerTypestate);
                ByteBuffer b = ByteBuffer.allocate(bytesPerTypestate);
                b.order(ByteOrder.LITTLE_ENDIAN);

                for (TypeState state : typestates) {
                    b.clear();
                    zero.clear();

                    b.put(zero);

                    for (AnalysisType t : state.types(bb)) {
                        Integer maybeId = typeIdMap.get(t.getId());
                        if(maybeId == null)
                            continue;
                        int id = maybeId;
                        int byte_index = id / 8;
                        int bit_index = id % 8;
                        byte old = b.get(byte_index);
                        old |= (byte) (1 << bit_index);
                        b.put(byte_index, old);
                    }

                    b.flip();
                    c.write(b);
                }
            }

            try (FileOutputStream out = new FileOutputStream("typeflow_methods.bin")) {
                FileChannel c = out.getChannel();

                ByteBuffer b = ByteBuffer.allocate(4);
                b.order(ByteOrder.LITTLE_ENDIAN);

                for (FlowNode f : flowsSorted) {
                    int mid = f.containing == null ? 0 : methodIdMap.get(f.containing);

                    b.putInt(mid);
                    b.flip();
                    c.write(b);
                    b.flip();
                }
            }
        }


        private static Map<Integer, Integer> makeDenseTypeIdMap(BigBang bb, Predicate<AnalysisType> shouldBeIncluded) {
            ArrayList<AnalysisType> typesInPreorder = new ArrayList<>();

            // Execute inorder-tree-traversal on subclass hierarchy in order to have hierarchy subtrees in one contiguous id range
            Stack<AnalysisType> worklist = new Stack<>();
            worklist.add(bb.getUniverse().objectType());

            while (!worklist.empty()) {
                AnalysisType u = worklist.pop();

                if (shouldBeIncluded.test(u))
                    typesInPreorder.add(u);

                for (AnalysisType v : u.getSubTypes()) {
                    if (v != u && !v.isInterface()) {
                        worklist.push(v);
                    }
                }
            }

            // Add interfaces at the end
            for (AnalysisType t : bb.getAllInstantiatedTypes()) {
                if (shouldBeIncluded.test(t) && t.isInterface()) {
                    typesInPreorder.add(t);
                }
            }

            HashMap<Integer, Integer> idMap = new HashMap<>(typesInPreorder.size());

            int newId = 0;
            for (AnalysisType t : typesInPreorder) {
                idMap.put(t.getId(), newId);
                newId++;
            }

            return idMap;
        }

        private static AnalysisType[] getRelevantTypes(PointsToAnalysis bb, Map<Integer, Integer> typeIdMap) {
            AnalysisType[] types = new AnalysisType[typeIdMap.size()];

            for (AnalysisType t : bb.getAllInstantiatedTypes())
                types[typeIdMap.get(t.getId())] = t;

            return types;
        }
    }

    /*
    private void addFeatureCallbackCausality(Graph g, PointsToAnalysis bb, HashMap<AnalysisMethod, Graph.RealMethodNode> methodMapping, HashMap<TypeFlow<?>, Graph.RealFlowNode> flowMapping) {

        HashMap<Consumer<Feature.DuringAnalysisAccess>, Graph.FeatureCallbackNode> callbackMapping = new HashMap<>();

        for(Map.Entry<Consumer<Feature.DuringAnalysisAccess>, List<AnalysisElement>> e : callbackReasons.entrySet()) {
            Graph.FeatureCallbackNode featureNode = callbackMapping.computeIfAbsent(e.getKey(), Graph.FeatureCallbackNode::new);

            for(AnalysisElement element : e.getValue())
            {
                if(!element.isReachable())
                    continue;

                if(element instanceof AnalysisMethod)
                {
                    g.directInvokes.add(new Graph.DirectCallEdge(methodMapping.get((AnalysisMethod)element), featureNode));
                } else if(element instanceof AnalysisType) {
                    AnalysisType t = (AnalysisType) element;
                    g.virtualInvokes.put(new Graph.VirtualCallEdge(flowMapping.get(t.instantiatedTypes), featureNode), TypeState.forExactType(bb, t, false));
                } else if(element instanceof AnalysisField) {
                    AnalysisField f = (AnalysisField) element;
                    TypeFlow<?> flow = f.isStatic() ? f.getStaticFieldFlow() : f.getInstanceFieldFlow();
                    g.virtualInvokes.put(new Graph.VirtualCallEdge(flowMapping.get(flow), featureNode), f.getType().getAssignableTypes(true));
                }
            }
        }

        for(Pair<Consumer<Feature.DuringAnalysisAccess>, Object> p : reachableThroughFeatureCallback) {
            Graph.FeatureCallbackNode featureCallbackNode = callbackMapping.get(p.getLeft());
            Object reached = p.getRight();

            AnalysisElement element = null;

            if(reached instanceof Executable)
            {
                AnalysisMethod m = bb.getMetaAccess().lookupJavaMethod((Executable) reached);
                element = m;
                g.directInvokes.add(new Graph.DirectCallEdge(featureCallbackNode, methodMapping.get(m)));
            } else if(reached instanceof Field) {
                AnalysisField f = bb.getMetaAccess().lookupJavaField((Field) reached);
                element = f;
                TypeFlow<?> flow = f.isStatic() ? f.getStaticFieldFlow() : f.getInstanceFieldFlow();
                Graph.FlowNode logicalFeatureNodeFlow = new Graph.FlowNode(featureCallbackNode);
                g.interflows.put(new Graph.FlowEdge(flowMapping.get(f.getType().instantiatedTypes), logicalFeatureNodeFlow), f.getType().getAssignableTypes(false));
                g.interflows.put(new Graph.FlowEdge(logicalFeatureNodeFlow, flowMapping.get(flow)), f.getType().getAssignableTypes(false));
            } else if(reached instanceof Class<?>) {
                AnalysisType t = bb.getMetaAccess().lookupJavaType((Class<?>) reached);
                element = t;
                Graph.FlowNode logicalFeatureNodeFlow = new Graph.FlowNode(featureCallbackNode);
                g.interflows.put(new Graph.FlowEdge(flowMapping.get(t.instantiatedTypes), logicalFeatureNodeFlow), TypeState.forExactType(bb, t, false));

                TypeState typeState = TypeState.forExactType(bb, t, true);
                TypeState typeStateNonNull = TypeState.forExactType(bb, t, false);

                t.forAllSuperTypes(_t -> {
                    g.interflows.put(new Graph.FlowEdge(logicalFeatureNodeFlow, flowMapping.get(_t.instantiatedTypes)), typeState);
                    g.interflows.put(new Graph.FlowEdge(logicalFeatureNodeFlow, flowMapping.get(_t.instantiatedTypesNonNull)), typeStateNonNull);
                });
            }

            rootCount.computeIfPresent(element, (key, count) -> count - 1);
        }

        for(Map.Entry<AnalysisElement, Integer> root : rootCount.entrySet())
        {
            if(root.getValue() < 0)
                throw new RuntimeException("Negative root reference count!");

            if(root.getValue() == 0)
            {
                if(root.getKey() instanceof AnalysisMethod)
                {
                    AnalysisMethod m = (AnalysisMethod)root.getKey();
                    System.err.println("Unrooted: " + m.getQualifiedName());
                    g.directInvokes.remove(new Graph.DirectCallEdge(null, methodMapping.get(m)));
                }
            }
        }
    }
    */

    /*
    private Set<AnalysisField> calcUnrootedFields(PointsToAnalysis bb)
    {
        HashSet<AnalysisField> unrootedFields = new HashSet<>();

        for(Pair<AnalysisElement, Field> p : unrootRegisteredFields)
        {
            AnalysisField f = bb.getMetaAccess().lookupJavaField(p.getRight());
            unrootedFields.add(f);

            if(p.getLeft() instanceof AnalysisMethod)
            {
                addDirectInvoke((AnalysisMethod)p.getLeft(), m);
            }
            else if(p.getLeft() instanceof AnalysisType)
            {
                AnalysisType t = (AnalysisType)p.getLeft();
                this.addVirtualInvoke(bb, t.instantiatedTypes, m, TypeState.forExactType(bb, t, false));
            }
            else if(p.getLeft() instanceof AnalysisField)
            {
                AnalysisField f = (AnalysisField)p.getLeft();
                TypeFlow<?> flow = f.isStatic() ? f.getStaticFieldFlow() : f.getInstanceFieldFlow();
                this.addVirtualInvoke(bb, flow, m, f.getType().getAssignableTypes(true));
            }
        }

        return unrootedMethods;
    }*/

    public synchronized void dump(PointsToAnalysis bb) throws java.io.IOException {
        Impl data = new Impl(instancesOfAllThreads, bb);
        Graph g = data.createCausalityGraph(bb);
        g.export(bb);
    }
}

