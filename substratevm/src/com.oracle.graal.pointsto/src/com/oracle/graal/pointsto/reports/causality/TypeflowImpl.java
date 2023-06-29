package com.oracle.graal.pointsto.reports.causality;

import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.flow.AbstractVirtualInvokeTypeFlow;
import com.oracle.graal.pointsto.flow.ActualParameterTypeFlow;
import com.oracle.graal.pointsto.flow.ActualReturnTypeFlow;
import com.oracle.graal.pointsto.flow.AllInstantiatedTypeFlow;
import com.oracle.graal.pointsto.flow.TypeFlow;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.typestate.TypeState;
import org.graalvm.collections.Pair;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

public class TypeflowImpl extends Impl {
    private final HashSet<Pair<TypeFlow<?>, TypeFlow<?>>> interflows = new HashSet<>();
    private final HashMap<AnalysisMethod, Pair<Set<AbstractVirtualInvokeTypeFlow>, TypeState>> virtual_invokes = new HashMap<>();

    /**
     * Saves for each virtual invocation the receiver typeflow before it may have been replaced during saturation.
     */
    private final HashMap<AbstractVirtualInvokeTypeFlow, TypeFlow<?>> originalInvokeReceivers = new HashMap<>();
    private final HashMap<Pair<Event, TypeFlow<?>>, TypeState> flowingFromHeap = new HashMap<>();


    public TypeflowImpl() {
    }

    public TypeflowImpl(Iterable<TypeflowImpl> instances, PointsToAnalysis bb) {
        super(instances, bb);
        for (TypeflowImpl i : instances) {
            interflows.addAll(i.interflows);
            mergeMap(virtual_invokes, i.virtual_invokes, (p1, p2) -> {
                p1.getLeft().addAll(p2.getLeft());
                return Pair.create(p1.getLeft(), TypeState.forUnion(bb, p1.getRight(), p2.getRight()));
            });
            originalInvokeReceivers.putAll(i.originalInvokeReceivers);
            mergeTypeFlowMap(flowingFromHeap, i.flowingFromHeap, bb);
        }
    }

    @Override
    public void registerTypeFlowEdge(TypeFlow<?> from, TypeFlow<?> to) {
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
    public void beginSaturationHappening() {
        currentlySaturatingDepth++;
    }

    @Override
    public void endSaturationHappening() {
        currentlySaturatingDepth--;
        if (currentlySaturatingDepth < 0)
            throw new RuntimeException();
    }

    @Override
    public void registerTypesEntering(PointsToAnalysis bb, Event cause, TypeFlow<?> destination, TypeState types) {
        flowingFromHeap.compute(Pair.create(cause, destination), (p, state) -> state == null ? types : TypeState.forUnion(bb, state, types));
    }



    @Override
    public void registerVirtualInvocation(PointsToAnalysis bb, AbstractVirtualInvokeTypeFlow invocation, AnalysisMethod concreteTargetMethod, AnalysisType concreteTargetType) {
        virtual_invokes.compute(concreteTargetMethod, (m, p) -> {
            if (p == null) {
                HashSet<AbstractVirtualInvokeTypeFlow> invocations = new HashSet<>(1);
                invocations.add(invocation);
                return Pair.create(invocations, TypeState.forExactType(bb, concreteTargetType, false));
            } else {
                p.getLeft().add(invocation);
                return Pair.create(p.getLeft(), TypeState.forUnion(bb, p.getRight(), TypeState.forExactType(bb, concreteTargetType, false)));
            }
        });
    }

    @Override
    public void addVirtualInvokeTypeFlow(AbstractVirtualInvokeTypeFlow invocation) {
        originalInvokeReceivers.put(invocation, invocation.method() == null ? null : invocation.getReceiver());
    }

    protected void forEachTypeflow(Consumer<TypeFlow<?>> callback) {
        for (var e : interflows) {
            callback.accept(e.getLeft());
            callback.accept(e.getRight());
        }
    }

    @Override
    protected void forEachEvent(Consumer<Event> callback) {
        super.forEachEvent(callback);

        flowingFromHeap.keySet().stream().map(Pair::getLeft).forEach(callback);
        virtual_invokes.keySet().stream().map(MethodReachable::new).forEach(callback);

        forEachTypeflow(tf -> {
            if(tf != null && tf.method() != null) {
                callback.accept(new MethodCode(tf.method()));
                callback.accept(new MethodReachable(tf.method()));
            }
        });
    }

    @Override
    public Graph createCausalityGraph(PointsToAnalysis bb) {
        Graph g = super.createCausalityGraph(bb);

        HashMap<TypeFlow<?>, Graph.RealFlowNode> flowMapping = new HashMap<>();

        Function<TypeFlow<?>, Graph.RealFlowNode> flowMapper = flow ->
        {
            if(flow.getState().typesCount() == 0 && !flow.isSaturated())
                return null;

            return flowMapping.computeIfAbsent(flow, f -> {
                AnalysisMethod m = f.method();

                Event reason = m == null ? null : new MethodCode(m);

                if(reason != null && reason.unused())
                    return null;

                return Graph.RealFlowNode.create(bb, f, reason);
            });
        };

        for (Map.Entry<AnalysisMethod, Pair<Set<AbstractVirtualInvokeTypeFlow>, TypeState>> e : virtual_invokes.entrySet()) {

            Event reason = new MethodReachable(e.getKey());

            if(reason.unused())
                continue;

            Graph.InvocationFlowNode invocationFlowNode = new Graph.InvocationFlowNode(reason, e.getValue().getRight());

            for (var invokeFlow : e.getValue().getLeft()) {
                TypeFlow<?> receiver = originalInvokeReceivers.get(invokeFlow);

                if (receiver == null) {
                    // Root invocation
                    Graph.FlowNode rootCallFlow = new Graph.FlowNode(
                            "Root call to " + invokeFlow.getTargetMethod(),
                            new RootMethodRegistration(invokeFlow.getTargetMethod()),
                            bb.getAllInstantiatedTypeFlow().getState());

                    g.interflows.add(new Graph.FlowEdge(
                            flowMapper.apply(invokeFlow.getTargetMethod().getDeclaringClass().instantiatedTypes),
                            rootCallFlow
                    ));
                    g.interflows.add(new Graph.FlowEdge(
                            rootCallFlow,
                            invocationFlowNode
                    ));
                } else  {
                    Graph.FlowNode receiverNode = flowMapper.apply(receiver);
                    if(receiverNode != null) {
                        g.interflows.add(new Graph.FlowEdge(
                                receiverNode,
                                invocationFlowNode
                        ));
                    }
                }
            }
        }

        for (Pair<TypeFlow<?>, TypeFlow<?>> e : interflows) {
            Graph.RealFlowNode left = null;

            if(e.getLeft() != null) {
                left = flowMapper.apply(e.getLeft());
                if(left == null)
                    continue;
            }

            Graph.RealFlowNode right = flowMapper.apply(e.getRight());
            if(right == null)
                continue;

            g.interflows.add(new Graph.FlowEdge(left, right));
        }

        for (AnalysisType t : bb.getAllInstantiatedTypes()) {
            TypeState state = TypeState.forExactType(bb, t, false);
            Graph.FlowNode vfn = new Graph.FlowNode("Virtual Flow Node for reaching " + t.toJavaName(), new TypeInstantiated(t), state);
            g.interflows.add(new Graph.FlowEdge(null, vfn));

            t.forAllSuperTypes(t1 -> {
                g.interflows.add(new Graph.FlowEdge(vfn, flowMapper.apply(t1.instantiatedTypes)));
                g.interflows.add(new Graph.FlowEdge(vfn, flowMapper.apply(t1.instantiatedTypesNonNull)));
            });
        }

        for (Map.Entry<Pair<Event, TypeFlow<?>>, TypeState> e : flowingFromHeap.entrySet()) {
            Graph.RealFlowNode fieldNode = flowMapper.apply(e.getKey().getRight());

            if (fieldNode == null)
                continue;

            // The causality-query implementation saturates at 20 types.
            // Saturation will happen even if the types don't pass the filter
            // Therefore, as soon as a typeflow connected to the source (represented by null) allows for more than 20 types,
            // These will be added to allInstantiated immeadiatly.
            // In practice this only shows in big projects and rarely (e.g. 3 times in 170MB spring-petclinic)
            // Therefore we simply employ this quick fix:
            if(e.getValue().typesCount() <= 20) {
                Graph.FlowNode intermediate = new Graph.FlowNode("Virtual Flow from Heap", e.getKey().getLeft(), e.getValue());
                g.interflows.add(new Graph.FlowEdge(null, intermediate));
                g.interflows.add(new Graph.FlowEdge(intermediate, fieldNode));
            } else {
                AnalysisType[] types = e.getValue().typesStream(bb).toArray(AnalysisType[]::new);

                for(int i = 0; i < types.length; i += 20) {
                    TypeState state = TypeState.forEmpty();

                    for(int j = i; j < i + 20 && j < types.length; j++) {
                        state = TypeState.forUnion(bb, state, TypeState.forExactType(bb, types[j], false));
                    }

                    Graph.FlowNode intermediate = new Graph.FlowNode("Virtual Flow from Heap", e.getKey().getLeft(), state);
                    g.interflows.add(new Graph.FlowEdge(null, intermediate));
                    g.interflows.add(new Graph.FlowEdge(intermediate, fieldNode));
                }
            }
        }

        return g;
    }
}
