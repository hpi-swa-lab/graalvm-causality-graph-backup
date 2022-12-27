package com.oracle.graal.pointsto.reports.causality;

import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.flow.AbstractVirtualInvokeTypeFlow;
import com.oracle.graal.pointsto.flow.ActualParameterTypeFlow;
import com.oracle.graal.pointsto.flow.ActualReturnTypeFlow;
import com.oracle.graal.pointsto.flow.AllInstantiatedTypeFlow;
import com.oracle.graal.pointsto.flow.InvokeTypeFlow;
import com.oracle.graal.pointsto.flow.MethodTypeFlow;
import com.oracle.graal.pointsto.flow.TypeFlow;
import com.oracle.graal.pointsto.meta.AnalysisElement;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.reports.CausalityExport;
import com.oracle.graal.pointsto.typestate.TypeState;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaMethod;
import org.graalvm.collections.Pair;
import org.graalvm.nativeimage.hosted.Feature;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Impl extends CausalityExport {
    // All typeflows ever constructed
    private final HashSet<TypeFlow<?>> typeflows = new HashSet<>();
    private final HashSet<Pair<TypeFlow<?>, TypeFlow<?>>> interflows = new HashSet<>();
    private final HashSet<Pair<AnalysisMethod, AnalysisMethod>> direct_invokes = new HashSet<>();
    private final HashMap<AnalysisMethod, Pair<Set<AbstractVirtualInvokeTypeFlow>, TypeState>> virtual_invokes = new HashMap<>();
    private final HashMap<TypeFlow<?>, AnalysisMethod> typeflowGateMethods = new HashMap<>();
    private final HashMap<AnalysisElement, Integer> rootCount = new HashMap<>();
    private final HashSet<Class<?>> unresolvedRootTypes = new HashSet<>();
    // Boolean stores whether the type is also instantiated
    private final HashMap<Pair<AnalysisMethod, AnalysisType>, Boolean> methodsReachingTypes = new HashMap<>();
    private final Map<Consumer<Feature.DuringAnalysisAccess>, List<AnalysisElement>> callbackReasons = new HashMap<>();
    private final HashSet<Pair<Consumer<Feature.DuringAnalysisAccess>, Object>> reachableThroughFeatureCallback = new HashSet<>();

    private Consumer<Feature.DuringAnalysisAccess> runningNotification = null;

    private final HashMap<InvokeTypeFlow, TypeFlow<?>> originalInvokeReceivers = new HashMap<>();
    private final HashMap<Pair<Class<?>, TypeFlow<?>>, TypeState> flowingFromHeap = new HashMap<>();

    public Impl() {
    }

    private static <K, V> void mergeMap(Map<K, V> dst, Map<K, V> src, BiFunction<V, V, V> merger) {
        src.forEach((k, v) -> dst.merge(k, v, merger));
    }

    private static <K> void mergeTypeFlowMap(Map<K, TypeState> dst, Map<K, TypeState> src, PointsToAnalysis bb) {
        mergeMap(dst, src, (v1, v2) -> TypeState.forUnion(bb, v1, v2));
    }

    public Impl(List<Impl> instances, PointsToAnalysis bb) {
        for (Impl i : instances) {
            typeflows.addAll(i.typeflows);
            interflows.addAll(i.interflows);
            direct_invokes.addAll(i.direct_invokes);
            mergeMap(virtual_invokes, i.virtual_invokes, (p1, p2) -> {
                p1.getLeft().addAll(p2.getLeft());
                return Pair.create(p1.getLeft(), TypeState.forUnion(bb, p1.getRight(), p2.getRight()));
            });
            typeflowGateMethods.putAll(i.typeflowGateMethods);
            i.rootCount.forEach((k, v) -> rootCount.merge(k, v, Integer::sum));
            unresolvedRootTypes.addAll(i.unresolvedRootTypes);
            mergeMap(methodsReachingTypes, i.methodsReachingTypes, Boolean::logicalOr);
            mergeMap(callbackReasons, i.callbackReasons, (l1, l2) -> Stream.concat(l1.stream(), l2.stream()).collect(Collectors.toList()));
            reachableThroughFeatureCallback.addAll(i.reachableThroughFeatureCallback);
            originalInvokeReceivers.putAll(i.originalInvokeReceivers);
            mergeTypeFlowMap(flowingFromHeap, i.flowingFromHeap, bb);
        }
    }

    @Override
    public void addTypeFlow(TypeFlow<?> flow) {
        typeflows.add(flow);
    }

    @Override
    public void addTypeFlowEdge(TypeFlow<?> from, TypeFlow<?> to) {
        if (from == to)
            return;

        if (currentlySaturatingDepth > 0)
            if (from instanceof AllInstantiatedTypeFlow)
                return;
            else
                assert to.isContextInsensitive() || from instanceof ActualReturnTypeFlow && to instanceof ActualReturnTypeFlow || to instanceof ActualParameterTypeFlow;

        interflows.add(Pair.create(from, to));
    }

    int currentlySaturatingDepth; // Inhibits the registration of new typeflow edges

    @Override
    public void setSaturationHappening(boolean currentlySaturating) {
        if (currentlySaturating)
            currentlySaturatingDepth++;
        else
            currentlySaturatingDepth--;

        if (currentlySaturatingDepth < 0)
            throw new RuntimeException();
    }

    @Override
    public void addTypeFlowFromHeap(PointsToAnalysis analysis, Class<?> creason, TypeFlow<?> fieldTypeFlow, AnalysisType flowingType) {
        TypeState newState = TypeState.forExactType(analysis, flowingType, false);
        flowingFromHeap.compute(Pair.create(creason, fieldTypeFlow), (p, state) -> state == null ? newState : TypeState.forUnion(analysis, state, newState));
    }

    @Override
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

    @Override
    public void addVirtualInvoke(PointsToAnalysis bb, AbstractVirtualInvokeTypeFlow invocation, AnalysisMethod concreteTargetMethod, TypeState concreteTargetMethodCallingTypes) {
        virtual_invokes.compute(concreteTargetMethod, (m, p) -> {
            if (p == null) {
                HashSet<AbstractVirtualInvokeTypeFlow> invocations = new HashSet<>(1);
                invocations.add(invocation);
                return Pair.create(invocations, concreteTargetMethodCallingTypes);
            } else {
                p.getLeft().add(invocation);
                return Pair.create(p.getLeft(), TypeState.forUnion(bb, p.getRight(), concreteTargetMethodCallingTypes));
            }
        });
    }

    @Override
    public void registerMethodFlow(MethodTypeFlow method) {
        for (TypeFlow<?> flow : method.getMethodFlowsGraph().flows()) {
            if (method.getMethod() == null)
                throw new RuntimeException("Null method registered");
            if (flow.method() != null)
                typeflowGateMethods.put(flow, method.getMethod());
        }
    }

    @Override
    public void registerVirtualInvokeTypeFlow(AbstractVirtualInvokeTypeFlow invocation) {
        originalInvokeReceivers.put(invocation, invocation.getReceiver());
    }

    @Override
    public void registerTypeReachableRoot(AnalysisType type, boolean instantiated) {
        methodsReachingTypes.compute(Pair.create(null, type), (t, wasInstantiated) -> wasInstantiated == null ? instantiated : wasInstantiated | instantiated);
    }

    @Override
    public void registerTypeReachableRoot(Class<?> type) {
        unresolvedRootTypes.add(type);
    }

    @Override
    public void registerTypeReachableThroughHeap(AnalysisType type, JavaConstant object, boolean instantiated) {
        if (type.getName().equals("Lorg/apache/log4j/helpers/NullEnumeration;"))
            return;

        methodsReachingTypes.compute(Pair.create(null, type), (t, wasInstantiated) -> wasInstantiated == null ? instantiated : wasInstantiated | instantiated); // TODO: Make build-time clinit accountable
    }

    @Override
    public void registerTypeReachableByMethod(AnalysisType type, JavaMethod m, boolean instantiated) {
        methodsReachingTypes.compute(Pair.create((AnalysisMethod) m, type), (t, wasInstantiated) -> wasInstantiated == null ? instantiated : wasInstantiated | instantiated);
    }

    @Override
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

    @Override
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

    @Override
    public void registerNotificationStart(Consumer<Feature.DuringAnalysisAccess> notification) {
        if (runningNotification != null)
            throw new RuntimeException("Notification already running!");

        runningNotification = notification;
    }

    @Override
    public void registerNotificationEnd(Consumer<Feature.DuringAnalysisAccess> notification) {
        if (runningNotification != notification)
            throw new RuntimeException("Notification was not running!");

        runningNotification = null;
    }

    private void registerAnonymousRegistration(Object unresolvedElement) {
        Consumer<Feature.DuringAnalysisAccess> running = runningNotification;

        if (running != null) {
            reachableThroughFeatureCallback.add(Pair.create(running, unresolvedElement));
        }
    }

    @Override
    public void registerAnonymousRegistration(Executable e) {
        registerAnonymousRegistration((Object) e);
    }

    @Override
    public void registerAnonymousRegistration(Field f) {
        registerAnonymousRegistration((Object) f);
    }

    @Override
    public void registerAnonymousRegistration(Class<?> c) {
        registerAnonymousRegistration((Object) c);
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


            for (AnalysisType t : reached) {
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
                if (f.getState().typesCount() != 0 || f.isSaturated()) {
                    AnalysisMethod m = typeflowGateMethods.get(f);
                    Graph.MethodNode n = null;
                    if (m != null) {
                        n = methodMapping.get(m);
                        if (n == null)
                            continue;
                    }
                    flowMapping.put(f, Graph.RealFlowNode.create(bb, f, n));
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

        for (Map.Entry<AnalysisMethod, Pair<Set<AbstractVirtualInvokeTypeFlow>, TypeState>> e : virtual_invokes.entrySet()) {
            Graph.MethodNode mNode = methodMapping.get(e.getKey());

            if (mNode == null)
                continue;

            Graph.InvocationFlowNode invocationFlowNode = new Graph.InvocationFlowNode(mNode, e.getValue().getRight());

            e.getValue().getLeft().stream()
                    .map(originalInvokeReceivers::get)
                    .filter(Objects::nonNull)
                    .map(flowMapping::get)
                    .filter(Objects::nonNull)
                    .forEach(receiverNode -> g.interflows.add(new Graph.FlowEdge(receiverNode, invocationFlowNode)));
        }

        for (Pair<TypeFlow<?>, TypeFlow<?>> e : interflows) {
            if ((e.getLeft() != null && !flowMapping.containsKey(e.getLeft())) || !flowMapping.containsKey(e.getRight()))
                continue;

            g.interflows.add(new Graph.FlowEdge(e.getLeft() == null ? null : flowMapping.get(e.getLeft()), flowMapping.get(e.getRight())));
        }

        for (Pair<AnalysisMethod, AnalysisType> e : methodsReachingTypes.keySet()) {
            AnalysisMethod m = e.getLeft();

            Graph.RealMethodNode mnode;
            if (m == null) {
                mnode = null; // Intentionally null
            } else {
                mnode = methodMapping.get(m);
                if (mnode == null)
                    continue;
            }

            Graph.ClassReachableNode reachableNode = typeReachableMapping.get(e.getRight());

            if (reachableNode == null)
                continue;

            g.directInvokes.add(new Graph.DirectCallEdge(mnode, reachableNode));
        }

        for (Map.Entry<AnalysisType, Graph.ClassReachableNode> e : typeReachableMapping.entrySet()) {
            AnalysisMethod classInitializer = e.getKey().getClassInitializer();
            if (classInitializer != null && methodMapping.containsKey(classInitializer)) {
                g.directInvokes.add(new Graph.DirectCallEdge(e.getValue(), methodMapping.get(classInitializer)));
            }
        }

        for (Map.Entry<Pair<AnalysisMethod, AnalysisType>, Boolean> kv : methodsReachingTypes.entrySet()) {
            if (kv.getValue()) {
                AnalysisMethod m = kv.getKey().getLeft();

                Graph.RealMethodNode mnode;
                if (m == null) {
                    mnode = null; // Intentionally null
                } else {
                    mnode = methodMapping.get(m);
                    if (mnode == null)
                        continue;
                }

                AnalysisType t = kv.getKey().getRight();
                TypeState state = TypeState.forExactType(bb, t, false);

                String debugStr = "Virtual Flow Node for reaching " + t.toJavaName();
                if (m != null) {
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

        for (Map.Entry<Pair<Class<?>, TypeFlow<?>>, TypeState> e : flowingFromHeap.entrySet()) {
            Graph.MethodNode mNode = null;

            if (e.getKey().getLeft() != null) {
                mNode = typeReachableMapping.get(bb.getMetaAccess().lookupJavaType(e.getClass()));
            }

            Graph.RealFlowNode fieldNode = flowMapping.get(e.getKey().getRight());

            if (fieldNode == null)
                continue;

            Graph.FlowNode intermediate = new Graph.FlowNode("Virtual Flow from Heap: " + e.getValue(), mNode, e.getValue());
            g.interflows.add(new Graph.FlowEdge(null, intermediate));
            g.interflows.add(new Graph.FlowEdge(intermediate, fieldNode));
        }

        return g;
    }
}
