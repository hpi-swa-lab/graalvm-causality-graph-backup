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
import com.oracle.graal.pointsto.meta.AnalysisField;
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
import java.util.Stack;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Impl extends CausalityExport {
    // All typeflows ever constructed
    private final HashSet<TypeFlow<?>> typeflows = new HashSet<>();
    private final HashSet<Pair<TypeFlow<?>, TypeFlow<?>>> interflows = new HashSet<>();
    private final HashSet<Pair<Object, Object>> direct_edges = new HashSet<>();
    private final HashMap<AnalysisMethod, Pair<Set<AbstractVirtualInvokeTypeFlow>, TypeState>> virtual_invokes = new HashMap<>();
    private final HashMap<TypeFlow<?>, AnalysisMethod> typeflowGateMethods = new HashMap<>();
    private final HashSet<Pair<Object, Class<?>>> unresolvedRootTypes = new HashSet<>();
    // Boolean stores whether the type is also instantiated
    private final HashMap<Pair<Object, AnalysisType>, Boolean> reasonsReachingTypes = new HashMap<>();
    private final HashSet<Pair<AnalysisElement, Object>> analysisElementsReachingReasons = new HashSet<>();

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
            direct_edges.addAll(i.direct_edges);
            mergeMap(virtual_invokes, i.virtual_invokes, (p1, p2) -> {
                p1.getLeft().addAll(p2.getLeft());
                return Pair.create(p1.getLeft(), TypeState.forUnion(bb, p1.getRight(), p2.getRight()));
            });
            typeflowGateMethods.putAll(i.typeflowGateMethods);
            unresolvedRootTypes.addAll(i.unresolvedRootTypes);
            mergeMap(reasonsReachingTypes, i.reasonsReachingTypes, Boolean::logicalOr);
            analysisElementsReachingReasons.addAll(i.analysisElementsReachingReasons);
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
        Object reason = caller != null ? caller : rootReasons.empty() ? null : rootReasons.peek();
        direct_edges.add(Pair.create(reason, callee));
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
        Object reason = rootReasons.empty() ? null : rootReasons.peek();
        reasonsReachingTypes.compute(Pair.create(reason, type), (t, wasInstantiated) -> wasInstantiated == null ? instantiated : wasInstantiated | instantiated);
    }

    @Override
    public void registerTypeReachableRoot(Class<?> type) {
        Object reason = rootReasons.empty() ? null : rootReasons.peek();
        unresolvedRootTypes.add(Pair.create(reason, type));
    }

    @Override
    public void registerTypeReachableThroughHeap(AnalysisType type, JavaConstant object, boolean instantiated) {
        if (type.getName().equals("Lorg/apache/log4j/helpers/NullEnumeration;"))
            return;

        reasonsReachingTypes.compute(Pair.create(null, type), (t, wasInstantiated) -> wasInstantiated == null ? instantiated : wasInstantiated | instantiated); // TODO: Make build-time clinit accountable
    }

    @Override
    public void registerTypeReachableByMethod(AnalysisType type, JavaMethod m, boolean instantiated) {
        reasonsReachingTypes.compute(Pair.create((AnalysisMethod) m, type), (t, wasInstantiated) -> wasInstantiated == null ? instantiated : wasInstantiated | instantiated);
    }

    @Override
    public void registerTypeInstantiated(PointsToAnalysis bb, TypeFlow<?> cause, AnalysisType type) {
        type.forAllSuperTypes(t -> {
            addTypeFlowEdge(cause, t.instantiatedTypes);
            addTypeFlowEdge(cause, t.instantiatedTypesNonNull);
        });
    }

    @Override
    public void registerReachabilityNotification(AnalysisElement e, Consumer<Feature.DuringAnalysisAccess> callback) {
        analysisElementsReachingReasons.add(Pair.create(e, new CausalityExport.ReachabilityNotificationCallback(callback)));
    }

    @Override
    public void registerReasonRoot(CustomReason reason) {
        Object from = rootReasons.empty() ? null : rootReasons.peek();
        direct_edges.add(Pair.create(from, reason));
    }

    private final Stack<Object> rootReasons = new Stack<>();

    @Override
    protected void beginAccountingRootRegistrationsTo(CustomReason reason) {
        if(!rootReasons.empty())
            throw new RuntimeException("Oops! Thought that would never happen...");
        rootReasons.push(reason);
    }

    @Override
    protected void endAccountingRootRegistrationsTo(CustomReason reason) {
        if(rootReasons.empty() || rootReasons.pop() != reason) {
            throw new RuntimeException("Invalid Call to endAccountingRootRegistrationsTo()");
        }
    }




    public Graph createCausalityGraph(PointsToAnalysis bb) {
        Stream<Pair<Object, AnalysisType>> lateResolvedTypes = unresolvedRootTypes.stream().map(p -> Pair.create(p.getLeft(), bb.getMetaAccess().optionalLookupJavaType(p.getRight()))).filter(p -> p.getRight().isPresent()).map(p -> Pair.create(p.getLeft(), p.getRight().get()));
        lateResolvedTypes.forEach(t -> reasonsReachingTypes.putIfAbsent(t, false));
        unresolvedRootTypes.clear();

        Graph g = new Graph();

        HashMap<AnalysisMethod, Graph.RealMethodNode> methodMapping = new HashMap<>();
        HashMap<TypeFlow<?>, Graph.RealFlowNode> flowMapping = new HashMap<>();
        HashMap<AnalysisType, Graph.ClassReachableNode> typeReachableMapping = new HashMap<>();

        HashMap<Object, Graph.MethodNode> reasonMapping = new HashMap<>();

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


        for (Pair<Object, Object> e : direct_edges) {
            Graph.MethodNode from, to;

            if(e.getLeft() == null)
                from = null;
            else if(e.getLeft() instanceof AnalysisMethod) {
                from = methodMapping.get(e.getLeft());
                if(from == null)
                    continue;
            } else {
                from = reasonMapping.computeIfAbsent(e.getLeft(), Graph.ReasonMethodNode::new);
            }

            if(e.getRight() instanceof AnalysisMethod) {
                if(((AnalysisMethod)e.getRight()).isClassInitializer())
                    continue; // Re-account class-initializers to type instantiation!
                to = methodMapping.get(e.getRight());
                if(to == null)
                    continue;
            } else {
                to = reasonMapping.computeIfAbsent(e.getRight(), Graph.ReasonMethodNode::new);
            }

            g.directInvokes.add(new Graph.DirectCallEdge(from, to));
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

        for (Pair<Object, AnalysisType> e : reasonsReachingTypes.keySet()) {
            Object reason = e.getLeft();

            Graph.MethodNode mnode;
            if (reason == null) {
                mnode = null; // Intentionally null
            } else if(reason instanceof AnalysisMethod) {
                mnode = methodMapping.get(reason);
                if (mnode == null)
                    continue;
            } else {
                mnode = reasonMapping.computeIfAbsent(reason, Graph.ReasonMethodNode::new);
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

        for (Map.Entry<Pair<Object, AnalysisType>, Boolean> kv : reasonsReachingTypes.entrySet()) {
            if (kv.getValue()) {
                Object reason = kv.getKey().getLeft();

                Graph.MethodNode mnode;
                if (reason == null) {
                    mnode = null; // Intentionally null
                } else if(reason instanceof AnalysisMethod) {
                    mnode = methodMapping.get(reason);
                    if (mnode == null)
                        continue;
                } else {
                    mnode = reasonMapping.computeIfAbsent(reason, Graph.ReasonMethodNode::new);
                }

                AnalysisType t = kv.getKey().getRight();
                TypeState state = TypeState.forExactType(bb, t, false);

                String debugStr = "Virtual Flow Node for reaching " + t.toJavaName();
                if (mnode != null) {
                    debugStr += " due to " + mnode;
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

        for (Pair<AnalysisElement, Object> e : analysisElementsReachingReasons) {
            Graph.MethodNode from, to;

            if(e.getLeft() instanceof AnalysisType) {
                from = typeReachableMapping.get(e.getLeft());
            } else if(e.getLeft() instanceof AnalysisMethod) {
                from = methodMapping.get(e.getLeft());
                if(from == null)
                    continue;
            } else if(e.getLeft() instanceof AnalysisField) {
                // Causality-TODO: Introduce Field reachability into the causality graph. Until now, we model the reachability of a class to lead to all its fields being reachable, which is an overapproximation
                from = typeReachableMapping.get(((AnalysisField)e.getLeft()).getDeclaringClass());
            } else {
                throw new RuntimeException();
            }

            to = reasonMapping.computeIfAbsent(e.getRight(), Graph.ReasonMethodNode::new);

            g.directInvokes.add(new Graph.DirectCallEdge(from, to));
        }

        return g;
    }
}
