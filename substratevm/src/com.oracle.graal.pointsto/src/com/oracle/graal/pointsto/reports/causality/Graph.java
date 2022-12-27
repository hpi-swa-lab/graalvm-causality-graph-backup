package com.oracle.graal.pointsto.reports.causality;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.flow.AccessFieldTypeFlow;
import com.oracle.graal.pointsto.flow.AllInstantiatedTypeFlow;
import com.oracle.graal.pointsto.flow.ArrayElementsTypeFlow;
import com.oracle.graal.pointsto.flow.ConstantTypeFlow;
import com.oracle.graal.pointsto.flow.FieldTypeFlow;
import com.oracle.graal.pointsto.flow.FormalParamTypeFlow;
import com.oracle.graal.pointsto.flow.FormalReceiverTypeFlow;
import com.oracle.graal.pointsto.flow.NewInstanceTypeFlow;
import com.oracle.graal.pointsto.flow.OffsetLoadTypeFlow;
import com.oracle.graal.pointsto.flow.OffsetStoreTypeFlow;
import com.oracle.graal.pointsto.flow.SourceTypeFlow;
import com.oracle.graal.pointsto.flow.TypeFlow;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.reports.CausalityExport;
import com.oracle.graal.pointsto.typestate.TypeState;
import org.graalvm.nativeimage.hosted.Feature;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class Graph {
    static abstract class Node implements Comparable<Node> {
        private final String toStringCached;

        public Node(String debugStr) {
            toStringCached = debugStr;
        }

        public final String toString() {
            return toStringCached;
        }

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

    static class ReasonMethodNode extends MethodNode {
        public final Object reason;

        public ReasonMethodNode(Object reason) {
            super(reason.toString());
            this.reason = reason;
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

        // If this returns true, the "containing" MethodNode is to be interpreted as a method made reachable through this flow,
        // instead of a method needing to be reachable for this flow to be reachable
        public boolean makesContainingReachable() {
            return false;
        }
    }

    static final class InvocationFlowNode extends FlowNode {
        public InvocationFlowNode(MethodNode invocationTarget, TypeState filter) {
            super("Virtual Invocation Flow Node: " + invocationTarget, invocationTarget, filter);
        }

        @Override
        public boolean makesContainingReachable() {
            return true;
        }
    }

    static class DirectCallEdge {
        public final MethodNode from, to;

        DirectCallEdge(MethodNode from, MethodNode to) {
            if (to == null)
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

    static class FlowEdge {
        public final FlowNode from, to;

        FlowEdge(FlowNode from, FlowNode to) {
            if (to == null)
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

            if (f.method() != null) {
                str += " in " + f.method().getQualifiedName();
            }

            String detail = null;

            if (f instanceof ArrayElementsTypeFlow) {
                detail = ((ArrayElementsTypeFlow) f).getSourceObject().type().toJavaName();
            } else if (f instanceof FieldTypeFlow) {
                detail = ((FieldTypeFlow) f).getSource().format("%H.%n");
            } else if (f instanceof AccessFieldTypeFlow) {
                detail = ((AccessFieldTypeFlow) f).field().format("%H.%n");
            } else if (f instanceof NewInstanceTypeFlow || f instanceof ConstantTypeFlow || f instanceof SourceTypeFlow) {
                detail = f.getDeclaredType().toJavaName();
            } else if (f instanceof OffsetLoadTypeFlow) {
                detail = f.getDeclaredType().toJavaName();
            } else if (f instanceof OffsetStoreTypeFlow) {
                detail = f.getDeclaredType().toJavaName();
            } else if (f instanceof AllInstantiatedTypeFlow) {
                detail = f.getDeclaredType().toJavaName();
            } else if (f instanceof FormalParamTypeFlow && !(f instanceof FormalReceiverTypeFlow)) {
                detail = Integer.toString(((FormalParamTypeFlow) f).position());
            }

            if (detail != null)
                str += ": " + detail;

            return str;
        }

        static TypeState customFilter(PointsToAnalysis bb, TypeFlow<?> f) {
            if (f instanceof NewInstanceTypeFlow || f instanceof SourceTypeFlow || f instanceof ConstantTypeFlow) {
                return TypeState.forExactType(bb, f.getDeclaredType(), false);
            } else if (f instanceof FormalReceiverTypeFlow) {
                // No saturation happens here, therefore we can use all flowing types as empirical filter
                return f.getState();
            } else {
                return f.filter(bb, bb.getAllInstantiatedTypeFlow().getState());
            }
        }

        public RealFlowNode(TypeFlow<?> f, MethodNode containing, TypeState filter) {
            super(customToString(f), containing, filter);
            this.f = f;
        }

        public static RealFlowNode create(PointsToAnalysis bb, TypeFlow<?> f, MethodNode containing) {
            return new RealFlowNode(f, containing, customFilter(bb, f));
        }
    }

    public HashSet<DirectCallEdge> directInvokes = new HashSet<>();
    public HashSet<FlowEdge> interflows = new HashSet<>();

    private static <T> HashMap<T, Integer> inverse(T[] arr, int startIndex) {
        HashMap<T, Integer> idMap = new HashMap<>();

        int i = startIndex;
        for (T a : arr) {
            idMap.put(a, i);
            i++;
        }

        return idMap;
    }

    private static class SeenTypestates implements Iterable<TypeState> {
        private final ArrayList<TypeState> typestate_by_id = new ArrayList<>();
        private final HashMap<TypeState, Integer> typestate_to_id = new HashMap<>();

        private int assignId(TypeState s) {
            int size = typestate_by_id.size();
            typestate_by_id.add(s);
            return size;
        }

        ;

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
                    if (maybeId == null)
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

                if (f.makesContainingReachable())
                    mid |= Integer.MIN_VALUE; // Set MSB

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
