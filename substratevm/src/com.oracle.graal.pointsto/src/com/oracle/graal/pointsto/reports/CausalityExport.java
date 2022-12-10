package com.oracle.graal.pointsto.reports;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.flow.AllInstantiatedTypeFlow;
import com.oracle.graal.pointsto.flow.MethodTypeFlow;
import com.oracle.graal.pointsto.flow.TypeFlow;
import com.oracle.graal.pointsto.meta.AnalysisElement;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.meta.PointsToAnalysisType;
import com.oracle.graal.pointsto.typestate.TypeState;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class CausalityExport {

    public static final CausalityExport instance = new CausalityExport();

    // All typeflows ever constructed
    private final HashSet<TypeFlow<?>> typeflows = new HashSet<>();
    private final HashMap<Pair<TypeFlow<?>, TypeFlow<?>>, TypeState> interflows = new HashMap<>();
    private final HashSet<Pair<AnalysisMethod, AnalysisMethod>> direct_invokes = new HashSet<>();
    private final HashMap<Pair<TypeFlow<?>, AnalysisMethod>, TypeState> virtual_invokes = new HashMap<>();
    private final HashMap<TypeFlow<?>, AnalysisMethod> typeflowGateMethods = new HashMap<>();

    public synchronized void addTypeFlow(TypeFlow<?> flow) {
        typeflows.add(flow);
    }

    public synchronized void addFlowingTypes(PointsToAnalysis bb, TypeFlow<?> from, TypeFlow<?> to, TypeState addTypes) {
        TypeState newTypes = to.filter(bb, addTypes);
        interflows.compute(Pair.create(from, to), (edge, state) -> state == null ? newTypes : TypeState.forUnion(bb, state, newTypes));
    }

    private final HashMap<Integer, Integer> rootMethod_registerCount = new HashMap<>();

    public synchronized void addDirectInvoke(AnalysisMethod caller, AnalysisMethod callee) {
        if (caller == null && callee.getQualifiedName().contains("FactoryMethodHolder")) {
            System.err.println("Ignored " + callee.getQualifiedName());
            return;
        }

        if (caller == null) {
            rootMethod_registerCount.compute(callee.getId(), (id, count) -> (count == null ? 0 : count) + 1);
        }

        boolean alreadyExisting = !direct_invokes.add(Pair.create(caller, callee));
        assert !alreadyExisting : "Redundant adding of direct invoke";
    }

    public synchronized void addVirtualInvoke(PointsToAnalysis bb, TypeFlow<?> actualReceiver, AnalysisMethod concreteTargetMethod, TypeState concreteTargetMethodCallingTypes) {
        Pair<TypeFlow<?>, AnalysisMethod> e = Pair.create(actualReceiver, concreteTargetMethod);
        virtual_invokes.compute(e, (edge, state) -> state == null ? concreteTargetMethodCallingTypes : TypeState.forUnion(bb, state, concreteTargetMethodCallingTypes));
    }

    public synchronized void registerMethodFlow(MethodTypeFlow method) {
        for (TypeFlow<?> flow : method.getMethodFlowsGraph().getMiscFlows()) {
            if (method.getMethod() == null)
                throw new RuntimeException("Null method registered");
            if (flow.method() != null)
                typeflowGateMethods.put(flow, method.getMethod());
        }
    }

    public synchronized void registerTypeInstantiated(PointsToAnalysis bb, TypeFlow<?> cause, AnalysisType type) {
        TypeState typeState = TypeState.forExactType(bb, type, true);
        TypeState typeStateNonNull = TypeState.forExactType(bb, type, false);

        type.forAllSuperTypes(t -> {
            addFlowingTypes(bb, cause, t.instantiatedTypes, typeState);
            addFlowingTypes(bb, cause, t.instantiatedTypesNonNull, typeStateNonNull);
        });
    }

    Map<Consumer<Feature.DuringAnalysisAccess>, List<AnalysisElement>> callbackReasons = new HashMap<>();

    static boolean indirectlyImplementsFeature(Class<?> clazz) {
        if (clazz.equals(Feature.class))
            return true;

        for (Class<?> interfaces : clazz.getInterfaces())
            if (indirectlyImplementsFeature(interfaces))
                return true;

        return false;
    }

    public synchronized void registerReachabilityNotification(AnalysisElement e, Consumer<Feature.DuringAnalysisAccess> callback) {
        try {
            Class<?> callbackClass = Class.forName(callback.getClass().getName().split("\\$")[0]);

            if (!indirectlyImplementsFeature(callbackClass)) {
                System.err.println(callbackClass + " is not a Feature!");
            }

        } catch (ReflectiveOperationException ex) {
            System.err.println(ex);
        }

        callbackReasons.computeIfAbsent(callback, k -> new ArrayList<>()).add(e);
    }

    ThreadLocal<Consumer<Feature.DuringAnalysisAccess>> runningNotification = new ThreadLocal<>();

    public void registerNotificationStart(Consumer<Feature.DuringAnalysisAccess> notification) {
        if (runningNotification.get() != null)
            throw new RuntimeException("Notification already running!");

        runningNotification.set(notification);
    }

    public void registerNotificationEnd(Consumer<Feature.DuringAnalysisAccess> notification) {
        if (runningNotification.get() != notification)
            throw new RuntimeException("Notification was not running!");

        runningNotification.remove();
    }

    List<Pair<AnalysisElement, Executable>> unrootRegisteredMethods = new ArrayList<>();
    List<Pair<AnalysisElement, Field>> unrootRegisteredFields = new ArrayList<>();
    List<Pair<AnalysisElement, Class<?>>> unrootRegisteredClasses = new ArrayList<>();

    public synchronized void registerAnonymousRegistration(Executable e) {
        Consumer<Feature.DuringAnalysisAccess> running = runningNotification.get();

        if (running != null) {
            List<AnalysisElement> causes = callbackReasons.get(running);

            if (causes != null) {
                for (AnalysisElement cause : causes) {
                    unrootRegisteredMethods.add(Pair.create(cause, e));
                }
            }
        }
    }

    public synchronized void registerAnonymousRegistration(Field f) {
        Consumer<Feature.DuringAnalysisAccess> running = runningNotification.get();

        if (running != null) {
            List<AnalysisElement> causes = callbackReasons.get(running);

            if (causes != null) {
                for (AnalysisElement cause : causes) {
                    unrootRegisteredFields.add(Pair.create(cause, f));
                }
            }
        }
    }

    public synchronized void registerAnonymousRegistration(Class<?> c) {
        Consumer<Feature.DuringAnalysisAccess> running = runningNotification.get();

        if (running != null) {
            List<AnalysisElement> causes = callbackReasons.get(running);

            if (causes != null) {
                for (AnalysisElement cause : causes) {
                    unrootRegisteredClasses.add(Pair.create(cause, c));
                }
            }
        }
    }


    private HashSet<AnalysisMethod> accountClassInitializersToTypeInstantiation(PointsToAnalysis bb) {
        HashSet<AnalysisMethod> classInitializers = new HashSet<>();
        TypeState classInitializedClassesState = TypeState.forEmpty();

        HashSet<AllInstantiatedTypeFlow> allInstantiatedTypeFlows = new HashSet<>();

        for (AnalysisType t : bb.getUniverse().getTypes()) {
            PointsToAnalysisType t2 = (PointsToAnalysisType) t;
            allInstantiatedTypeFlows.add(t2.instantiatedTypes);
            allInstantiatedTypeFlows.add(t2.instantiatedTypesNonNull);
        }

        // --- Class-Initializer rechnen wir der reachability des Types an:
        for (AnalysisMethod m : bb.getUniverse().getMethods()) {
            if (m.isClassInitializer() && m.isImplementationInvoked() && m.getDeclaringClass().isReachable()) {
                classInitializers.add(m);
                addVirtualInvoke(bb, m.getDeclaringClass().getTypeFlow(bb, false), m, m.getDeclaringClass().getTypeFlow(bb, false).getState());
                classInitializedClassesState = TypeState.forUnion(bb, classInitializedClassesState, TypeState.forExactType(bb, m.getDeclaringClass(), false));
            }
        }

        for (Pair<TypeFlow<?>, TypeFlow<?>> e : interflows.keySet()) {
            if (e.getLeft() == null && allInstantiatedTypeFlows.contains(e.getRight()))
                interflows.put(e, TypeState.forSubtraction(bb, interflows.get(e), classInitializedClassesState));
        }

        return classInitializers;
    }

    static class Graph {
        static class Node implements Comparable<Node> {
            @Override
            public int compareTo(Node o) {
                return toString().compareTo(o.toString());
            }
        }

        static class MethodNode extends Node {
        }

        static class FeatureCallbackNode extends MethodNode {
            private final Consumer<Feature.DuringAnalysisAccess> callback;

            FeatureCallbackNode(Consumer<Feature.DuringAnalysisAccess> callback) {
                this.callback = callback;
            }

            @Override
            public String toString() {
                return callback.getClass().toString();
            }
        }

        static class FlowNode extends Node {
            public final MethodNode containing;

            FlowNode(MethodNode containing) {
                this.containing = containing;
            }
        }

        static class DirectCallEdge {
            public final MethodNode from, to;

            DirectCallEdge(MethodNode from, MethodNode to) {
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
        }

        static class VirtualCallEdge {
            public final FlowNode from;
            public final MethodNode to;

            VirtualCallEdge(FlowNode from, MethodNode to) {
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
                this.m = m;
            }

            @Override
            public String toString() {
                return m.getQualifiedName();
            }
        }

        static class RealFlowNode extends FlowNode {
            private final TypeFlow<?> f;

            public RealFlowNode(TypeFlow<?> f, MethodNode containing) {
                super(containing);
                this.f = f;
            }

            @Override
            public String toString() {
                return f.toString();
            }
        }

        public HashSet<DirectCallEdge> directInvokes = new HashSet<>();
        public HashMap<FlowEdge, TypeState> interflows = new HashMap<>();
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

            for (Map.Entry<FlowEdge, TypeState> e : interflows.entrySet()) {
                if (e.getKey().from != null) {
                    typeflows.add(e.getKey().from);
                    if (e.getKey().from.containing != null)
                        methods.add(e.getKey().from.containing);
                }
                typeflows.add(e.getKey().to);
                if (e.getKey().to.containing != null)
                    methods.add(e.getKey().to.containing);
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

            ArrayList<TypeState> typestate_by_id = new ArrayList<>();
            HashMap<TypeState, Integer> typestate_to_id = new HashMap<>();

            Function<TypeState, Integer> assignId = s -> {
                int size = typestate_by_id.size();
                typestate_by_id.add(s);
                return size;
            };

            try (FileOutputStream out = new FileOutputStream("interflows.bin")) {
                FileChannel c = out.getChannel();

                ByteBuffer b = ByteBuffer.allocate(12);
                b.order(ByteOrder.LITTLE_ENDIAN);

                for (Map.Entry<FlowEdge, TypeState> entry : interflows.entrySet()) {
                    int typestate_id = typestate_to_id.computeIfAbsent(entry.getValue(), assignId);

                    b.putInt(entry.getKey().from == null ? 0 : flowIdMap.get(entry.getKey().from));
                    b.putInt(flowIdMap.get(entry.getKey().to));
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
                    int typestate_id = typestate_to_id.computeIfAbsent(e.getValue(), assignId);

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

                for (TypeState state : typestate_by_id) {
                    b.clear();
                    zero.clear();

                    b.put(zero);

                    for (AnalysisType t : state.types(bb)) {
                        int id = typeIdMap.get(t.getId());
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

    private Set<AnalysisMethod> calcUnrootedMethods(PointsToAnalysis bb) {
        HashSet<AnalysisMethod> unrootedMethods = new HashSet<>();

        for (Pair<AnalysisElement, Executable> p : unrootRegisteredMethods) {
            AnalysisMethod m = bb.getMetaAccess().lookupJavaMethod(p.getRight());
            unrootedMethods.add(m);

            if (p.getLeft() instanceof AnalysisMethod) {
                addDirectInvoke((AnalysisMethod) p.getLeft(), m);
            } else if (p.getLeft() instanceof AnalysisType) {
                AnalysisType t = (AnalysisType) p.getLeft();
                this.addVirtualInvoke(bb, t.instantiatedTypes, m, TypeState.forExactType(bb, t, false));
            } else if (p.getLeft() instanceof AnalysisField) {
                AnalysisField f = (AnalysisField) p.getLeft();
                TypeFlow<?> flow = f.isStatic() ? f.getStaticFieldFlow() : f.getInstanceFieldFlow();
                this.addVirtualInvoke(bb, flow, m, f.getType().getAssignableTypes(true));
            }
        }

        return unrootedMethods;
    }

    /*/
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
        HashSet<AnalysisMethod> classInitializers = accountClassInitializersToTypeInstantiation(bb);
        direct_invokes.removeIf(call -> call.getLeft() == null && classInitializers.contains(call.getRight()));

        Graph g = new Graph();

        HashMap<AnalysisMethod, Graph.RealMethodNode> methodMapping = new HashMap<>();

        for (AnalysisMethod m : bb.getUniverse().getMethods()) {
            if (m.isReachable()) {
                methodMapping.put(m, new Graph.RealMethodNode(m));
            }
        }

        HashMap<TypeFlow<?>, Graph.RealFlowNode> flowMapping = new HashMap<>();

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

        for (Pair<AnalysisMethod, AnalysisMethod> e : direct_invokes) {
            if ((e.getLeft() != null && !methodMapping.containsKey(e.getLeft())) || !methodMapping.containsKey(e.getRight()))
                continue;

            g.directInvokes.add(new Graph.DirectCallEdge(e.getLeft() == null ? null : methodMapping.get(e.getLeft()), methodMapping.get(e.getRight())));
        }

        for (Map.Entry<Pair<TypeFlow<?>, AnalysisMethod>, TypeState> e : virtual_invokes.entrySet()) {
            if (!flowMapping.containsKey(e.getKey().getLeft()) || !methodMapping.containsKey(e.getKey().getRight()))
                continue;

            g.virtualInvokes.put(new Graph.VirtualCallEdge(flowMapping.get(e.getKey().getLeft()), methodMapping.get(e.getKey().getRight())), e.getValue());
        }

        for (Map.Entry<Pair<TypeFlow<?>, TypeFlow<?>>, TypeState> e : interflows.entrySet()) {
            if ((e.getKey().getLeft() != null && !flowMapping.containsKey(e.getKey().getLeft())) || !flowMapping.containsKey(e.getKey().getRight()))
                continue;

            g.interflows.put(new Graph.FlowEdge(e.getKey().getLeft() == null ? null : flowMapping.get(e.getKey().getLeft()), flowMapping.get(e.getKey().getRight())), e.getValue());
        }

        g.export(bb);
    }
}
