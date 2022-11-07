package com.oracle.graal.pointsto.reports;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.PointsToAnalysis;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class CausalityExport {

    public static final CausalityExport instance = new CausalityExport();

    private static class Edge {
        int src, dst;

        public Edge(int src, int dst)
        {
            this.src = src;
            this.dst = dst;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Edge)) return false;
            Edge edge = (Edge) o;
            return src == edge.src && dst == edge.dst;
        }

        @Override
        public int hashCode() {
            return Objects.hash(src, dst);
        }
    }

    private static class IntTriple
    {
        int a, b, c;

        public IntTriple(int a, int b, int c)
        {
            this.a = a;
            this.b = b;
            this.c = c;
        }
    }

    private final ArrayList<TypeFlow<?>> typeflows = new ArrayList<>();
    private final ArrayList<AnalysisMethod> methods = new ArrayList<>();
    private final HashMap<Edge, TypeState> interflows = new HashMap<>();
    private final HashSet<Edge> direct_invokes = new HashSet<>();
    private final HashMap<Edge, TypeState> virtual_invokes = new HashMap<>();
    private final ArrayList<AnalysisMethod> typeflow_methods = new ArrayList<>();

    private static <T> void addIncreasing(ArrayList<T> list, int idx, T elem)
    {
        if(idx >= list.size())
        {
            while(idx > list.size())
                list.add(null);

            list.add(elem);
        }
        else
        {
            if(list.get(idx) != null)
                throw new RuntimeException("addIncreasing does not accept double adds");
            list.set(idx, elem);
        }
    }

    public synchronized void addTypeFlow(TypeFlow<?> flow)
    {
        addIncreasing(typeflows, flow.id() - 1, flow);
    }

    public void addType(AnalysisType type)
    {
    }

    public synchronized void addMethod(AnalysisMethod method)
    {
        addIncreasing(methods, method.getId() - 1, method);
    }

    public synchronized void addFlowingTypes(PointsToAnalysis bb, TypeFlow<?> from, TypeFlow<?> to, TypeState addTypes)
    {
        Edge e = new Edge(from == null ? 0 : from.id(), to.id());
        TypeState cur = interflows.getOrDefault(e, TypeState.forEmpty());
        TypeState next = TypeState.forUnion(bb, cur, to.filter(bb, addTypes));
        if(next != cur)
            interflows.put(e, next);

        if(e.src > typeflows.size() || e.dst > typeflows.size())
            throw new RuntimeException();
    }

    public synchronized void addDirectInvoke(AnalysisMethod caller, AnalysisMethod callee)
    {
        if(caller == null && callee.getQualifiedName().contains("FactoryMethodHolder"))
        {
            System.err.println("Ignored " + callee.getQualifiedName());
            return;
        }

        Edge e = new Edge(caller == null ? 0 : caller.getId(), callee.getId());
        boolean alreadyExisting = !direct_invokes.add(e);
        assert !alreadyExisting : "Redundant adding of direct invoke";
    }

    public synchronized void addVirtualInvoke(PointsToAnalysis bb, TypeFlow<?> actualReceiver, AnalysisMethod concreteTargetMethod, TypeState concreteTargetMethodCallingTypes)
    {
        Edge e = new Edge(actualReceiver.id(), concreteTargetMethod.getId());
        TypeState cur = virtual_invokes.getOrDefault(e, TypeState.forEmpty());
        TypeState next = TypeState.forUnion(bb, cur, concreteTargetMethodCallingTypes);
        if(next != cur)
            virtual_invokes.put(e, next);
    }

    private void setContainingMethod(TypeFlow<?> flow, AnalysisMethod method)
    {
        addIncreasing(typeflow_methods, flow.id() - 1, method);
    }

    public synchronized void registerMethodFlow(MethodTypeFlow method)
    {
        for(TypeFlow<?> flow : method.getMethodFlowsGraph().getMiscFlows())
        {
            if(method.getMethod() == null)
                throw new RuntimeException("Null method registered");
            if(flow.method() != null)
                setContainingMethod(flow, method.getMethod());
        }
    }

    public synchronized void registerTypeInstantiated(PointsToAnalysis bb, TypeFlow<?> cause, AnalysisType type)
    {
        TypeState typeState = TypeState.forExactType(bb, type, true);
        TypeState typeStateNonNull = TypeState.forExactType(bb, type, false);

        type.forAllSuperTypes(t -> {
            addFlowingTypes(bb, cause, t.instantiatedTypes, typeState);
            addFlowingTypes(bb, cause, t.instantiatedTypesNonNull, typeStateNonNull);
        });
    }

    Map<Consumer<Feature.DuringAnalysisAccess>, List<AnalysisElement>> callbackReasons = new HashMap<>();

    static boolean indirectlyImplementsFeature(Class<?> clazz)
    {
        if(clazz.equals(Feature.class))
            return true;

        for(Class<?> interfaces : clazz.getInterfaces())
            if(indirectlyImplementsFeature(interfaces))
                return true;

        return false;
    }

    public synchronized void registerReachabilityNotification(AnalysisElement e, Consumer<Feature.DuringAnalysisAccess> callback)
    {
        try {
            Class<?> callbackClass = Class.forName(callback.getClass().getName().split("\\$")[0]);

            if(!indirectlyImplementsFeature(callbackClass))
            {
                System.err.println(callbackClass.toString() + " is not a Feature!");
            }

        } catch (ReflectiveOperationException ex) {
            System.err.println(ex);
        }

        callbackReasons.computeIfAbsent(callback, k -> new ArrayList<>()).add(e);
    }

    ThreadLocal<Consumer<Feature.DuringAnalysisAccess>> runningNotification = new ThreadLocal<>();

    public void registerNotificationStart(Consumer<Feature.DuringAnalysisAccess> notification)
    {
        if(runningNotification.get() != null)
            throw new RuntimeException("Notification already running!");

        runningNotification.set(notification);
    }

    public void registerNotificationEnd(Consumer<Feature.DuringAnalysisAccess> notification)
    {
        if(runningNotification.get() == null)
            throw new RuntimeException("Notification was not running!");

        runningNotification.remove();
    }

    List<Pair<AnalysisElement, Executable>> unrootRegistered = new ArrayList<>();

    public synchronized void registerAnonymousRegistration(Executable e)
    {
        Consumer<Feature.DuringAnalysisAccess> running = runningNotification.get();

        if(running != null)
        {
            List<AnalysisElement> causes = callbackReasons.get(running);

            if(causes != null)
            {
                for(AnalysisElement cause : causes)
                {
                    unrootRegistered.add(Pair.create(cause, e));
                }
            }
        }
    }

    public synchronized void dump(PointsToAnalysis bb) throws java.io.IOException
    {
        Map<Integer, Integer> typeIdMap = makeDenseTypeIdMap(bb, bb.getAllInstantiatedTypeFlow().getState()::containsType);

        AnalysisType[] types;

        {
            types = new AnalysisType[typeIdMap.size()];

            for(AnalysisType t : bb.getAllInstantiatedTypes())
                types[typeIdMap.get(t.getId())] = t;
        }

        while(typeflow_methods.size() < typeflows.size())
            typeflow_methods.add(null);


        HashSet<Integer> unrootedMethods = new HashSet<>();

        for(Pair<AnalysisElement, Executable> p : unrootRegistered)
        {
            AnalysisMethod m = bb.getMetaAccess().lookupJavaMethod(p.getRight());
            unrootedMethods.add(m.getId());

            if(p.getLeft() instanceof AnalysisMethod)
            {
                //addDirectInvoke(p.getLeft(), m);
            }
            else if(p.getLeft() instanceof AnalysisType)
            {
                //AnalysisType t = (AnalysisType)p.getLeft();
                //this.addVirtualInvoke(bb, t.instantiatedTypes, m, TypeState.forExactType(bb, t));
            }
            else if(p.getLeft() instanceof AnalysisField)
            {
                //AnalysisType t = ((AnalysisField)p.getLeft()).getType();
                //this.addVirtualInvoke(bb, t.instantiatedTypes, m, TypeState.forExactType(bb, t));
            }
        }

        HashSet<Integer> classInitializers = new HashSet<>();
        HashSet<Integer> classInitializedClasses = new HashSet<>();
        TypeState classInitializedClassesState = TypeState.forEmpty();

        HashSet<Integer> allInstantiatedTypeFlows = new HashSet<>();

        for(AnalysisType t : bb.getUniverse().getTypes())
        {
            PointsToAnalysisType t2 = (PointsToAnalysisType)t;
            allInstantiatedTypeFlows.add(t2.instantiatedTypes.id());
            allInstantiatedTypeFlows.add(t2.instantiatedTypesNonNull.id());
        }

        // --- Class-Initializer rechnen wir der reachability des Types an:
        for(AnalysisMethod m : methods)
        {
            if(m.isClassInitializer() && m.isImplementationInvoked() && m.getDeclaringClass().isReachable())
            {
                classInitializers.add(m.getId());
                addVirtualInvoke(bb, m.getDeclaringClass().getTypeFlow(bb, false), m, m.getDeclaringClass().getTypeFlow(bb, false).getState());
                classInitializedClasses.add(m.getDeclaringClass().getId());
                classInitializedClassesState = TypeState.forUnion(bb, classInitializedClassesState, TypeState.forExactType(bb, m.getDeclaringClass(), false));
            }
        }

        for(Edge e : interflows.keySet())
        {
            if(e.src == 0 && allInstantiatedTypeFlows.contains(e.dst))
                interflows.put(e, TypeState.forSubtraction(bb, interflows.get(e), classInitializedClassesState));
        }

        try(FileOutputStream out = new FileOutputStream("types.txt"))
        {
            try(PrintStream w = new PrintStream(out))
            {
                for(AnalysisType type : types)
                {
                    if(type == null)
                        w.println();
                    else
                        w.println(type.toJavaName());
                }
            }
        }

        try(FileOutputStream out = new FileOutputStream("methods.txt"))
        {
            try(PrintStream w = new PrintStream(out))
            {
                for(AnalysisMethod method : methods)
                {
                    if(method == null)
                        w.println();
                    else
                        w.println(method.getQualifiedName());
                }
            }
        }

        try(FileOutputStream out = new FileOutputStream("typeflows.txt"))
        {
            try(PrintStream w = new PrintStream(out))
            {
                for(TypeFlow<?> flow : typeflows)
                {
                    if(flow == null)
                        w.println();
                    else {
                        String formatted = flow.toString().replace('\n', ' ');
                        w.println(formatted);
                    }
                }
            }
        }

        try(FileOutputStream out = new FileOutputStream("direct_invokes.bin"))
        {
            FileChannel c = out.getChannel();

            ByteBuffer b = ByteBuffer.allocate(8);
            b.order(ByteOrder.LITTLE_ENDIAN);

            for(Edge e : direct_invokes)
            {
                if(e.src == 0 && unrootedMethods.contains(e.dst))
                    continue;

                if(e.src == 0 && classInitializers.contains(e.dst))
                    continue;

                b.putInt(e.src);
                b.putInt(e.dst);
                b.flip();
                c.write(b);
                b.flip();
            }
        }

        ArrayList<TypeState> typestate_by_id = new ArrayList<>();
        HashMap<TypeState, Integer> typestate_to_id = new HashMap<>();
        ArrayList<IntTriple> interflows_compact = new ArrayList<>(interflows.size());
        ArrayList<IntTriple> virtual_invokes_compact = new ArrayList<>(virtual_invokes.size());

        Function<TypeState, Integer> assignId = s -> {
            int size = typestate_by_id.size();
            typestate_by_id.add(s);
            return size;
        };

        for(Map.Entry<Edge, TypeState> entry : interflows.entrySet())
        {
            int typestate_id = typestate_to_id.computeIfAbsent(entry.getValue(), assignId);
            interflows_compact.add(new IntTriple(entry.getKey().src, entry.getKey().dst, typestate_id));
        }

        for(Map.Entry<Edge, TypeState> entry : virtual_invokes.entrySet())
        {
            int typestate_id = typestate_to_id.computeIfAbsent(entry.getValue(), assignId);
            virtual_invokes_compact.add(new IntTriple(entry.getKey().src, entry.getKey().dst, typestate_id));
        }

        try(FileOutputStream out = new FileOutputStream("typestates.bin"))
        {
            FileChannel c = out.getChannel();
            int bytesPerTypestate = (types.length + 7) / 8;

            ByteBuffer zero = ByteBuffer.allocate(bytesPerTypestate);
            ByteBuffer b = ByteBuffer.allocate(bytesPerTypestate);
            b.order(ByteOrder.LITTLE_ENDIAN);

            for(TypeState state : typestate_by_id)
            {
                b.clear();
                zero.clear();

                b.put(zero);

                for(AnalysisType t : state.types(bb))
                {
                    int id = typeIdMap.get(t.getId());
                    int byte_index = id / 8;
                    int bit_index = id % 8;
                    byte old = b.get(byte_index);
                    old |= (byte)(1 << bit_index);
                    b.put(byte_index, old);
                }

                b.flip();
                c.write(b);
            }
        }

        try(FileOutputStream out = new FileOutputStream("interflows.bin"))
        {
            FileChannel c = out.getChannel();

            ByteBuffer b = ByteBuffer.allocate(12);
            b.order(ByteOrder.LITTLE_ENDIAN);

            for(IntTriple triple : interflows_compact)
            {
                b.putInt(triple.a);
                b.putInt(triple.b);
                b.putInt(triple.c);
                b.flip();
                c.write(b);
                b.flip();
            }
        }

        try(FileOutputStream out = new FileOutputStream("virtual_invokes.bin"))
        {
            FileChannel c = out.getChannel();

            ByteBuffer b = ByteBuffer.allocate(12);
            b.order(ByteOrder.LITTLE_ENDIAN);

            for(IntTriple triple : virtual_invokes_compact)
            {
                b.putInt(triple.a);
                b.putInt(triple.b);
                b.putInt(triple.c);
                b.flip();
                c.write(b);
                b.flip();
            }
        }

        try(FileOutputStream out = new FileOutputStream("typeflow_methods.bin"))
        {
            FileChannel c = out.getChannel();

            ByteBuffer b = ByteBuffer.allocate(4);
            b.order(ByteOrder.LITTLE_ENDIAN);

            for(AnalysisMethod method : typeflow_methods)
            {
                int mid = method == null ? 0 : method.getId();

                b.putInt(mid);
                b.flip();
                c.write(b);
                b.flip();
            }
        }
    }

    static Map<Integer, Integer> makeDenseTypeIdMap(BigBang bb, Predicate<AnalysisType> shouldBeIncluded)
    {
        ArrayList<AnalysisType> typesInPreorder = new ArrayList<>();

        Stack<AnalysisType> worklist = new Stack<>();
        worklist.add(bb.getUniverse().objectType());

        while(!worklist.empty())
        {
            AnalysisType u = worklist.pop();

            if(shouldBeIncluded.test(u))
                typesInPreorder.add(u);

            for(AnalysisType v : u.getSubTypes())
            {
                if(v != u && !v.isInterface())
                {
                    worklist.push(v);
                }
            }
        }

        for(AnalysisType t : bb.getAllInstantiatedTypes())
        {
            if(shouldBeIncluded.test(t) && t.isInterface())
            {
                typesInPreorder.add(t);
            }
        }

        HashMap<Integer, Integer> idMap = new HashMap<>(typesInPreorder.size());

        int newId = 0;
        for(AnalysisType t : typesInPreorder)
        {
            idMap.put(t.getId(), newId);
            newId++;
        }

        return idMap;
    }
}