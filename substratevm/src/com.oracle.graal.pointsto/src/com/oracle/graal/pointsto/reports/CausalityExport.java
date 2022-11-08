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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class CausalityExport {

    public static final CausalityExport instance = new CausalityExport();

    // All typeflows ever constructed
    private final ArrayList<TypeFlow<?>> typeflows = new ArrayList<>();
    private final HashMap<Pair<TypeFlow<?>, TypeFlow<?>>, TypeState> interflows = new HashMap<>();
    private final HashSet<Pair<AnalysisMethod, AnalysisMethod>> direct_invokes = new HashSet<>();
    private final HashMap<Pair<TypeFlow<?>, AnalysisMethod>, TypeState> virtual_invokes = new HashMap<>();
    private final ArrayList<AnalysisMethod> typeflowGateMethods = new ArrayList<>();

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

    public synchronized void addFlowingTypes(PointsToAnalysis bb, TypeFlow<?> from, TypeFlow<?> to, TypeState addTypes)
    {
        TypeState newTypes = to.filter(bb, addTypes);
        interflows.compute(Pair.create(from, to), (edge, state) -> state == null ? newTypes : TypeState.forUnion(bb, state, newTypes));
    }

    private final HashMap<Integer, Integer> rootMethod_registerCount = new HashMap<>();

    public synchronized void addDirectInvoke(AnalysisMethod caller, AnalysisMethod callee)
    {
        if(caller == null && callee.getQualifiedName().contains("FactoryMethodHolder"))
        {
            System.err.println("Ignored " + callee.getQualifiedName());
            return;
        }

        if(caller == null)
        {
            rootMethod_registerCount.compute(callee.getId(), (id, count) -> (count == null ? 0 : count) + 1);
        }

        boolean alreadyExisting = !direct_invokes.add(Pair.create(caller, callee));
        assert !alreadyExisting : "Redundant adding of direct invoke";
    }

    public synchronized void addVirtualInvoke(PointsToAnalysis bb, TypeFlow<?> actualReceiver, AnalysisMethod concreteTargetMethod, TypeState concreteTargetMethodCallingTypes)
    {
        Pair<TypeFlow<?>, AnalysisMethod> e = Pair.create(actualReceiver, concreteTargetMethod);
        virtual_invokes.compute(e, (edge, state) -> state == null ? concreteTargetMethodCallingTypes : TypeState.forUnion(bb, state, concreteTargetMethodCallingTypes));
    }

    public synchronized void registerMethodFlow(MethodTypeFlow method)
    {
        for(TypeFlow<?> flow : method.getMethodFlowsGraph().getMiscFlows())
        {
            if(method.getMethod() == null)
                throw new RuntimeException("Null method registered");
            if(flow.method() != null)
                addIncreasing(typeflowGateMethods, flow.id() - 1, method.getMethod());
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
                System.err.println(callbackClass + " is not a Feature!");
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
        if(runningNotification.get() != notification)
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

    private HashSet<AnalysisMethod> accountClassInitializersToTypeInstantiation(PointsToAnalysis bb)
    {
        HashSet<AnalysisMethod> classInitializers = new HashSet<>();
        TypeState classInitializedClassesState = TypeState.forEmpty();

        HashSet<AllInstantiatedTypeFlow> allInstantiatedTypeFlows = new HashSet<>();

        for(AnalysisType t : bb.getUniverse().getTypes())
        {
            PointsToAnalysisType t2 = (PointsToAnalysisType)t;
            allInstantiatedTypeFlows.add(t2.instantiatedTypes);
            allInstantiatedTypeFlows.add(t2.instantiatedTypesNonNull);
        }

        // --- Class-Initializer rechnen wir der reachability des Types an:
        for(AnalysisMethod m : bb.getUniverse().getMethods())
        {
            if(m.isClassInitializer() && m.isImplementationInvoked() && m.getDeclaringClass().isReachable())
            {
                classInitializers.add(m);
                addVirtualInvoke(bb, m.getDeclaringClass().getTypeFlow(bb, false), m, m.getDeclaringClass().getTypeFlow(bb, false).getState());
                classInitializedClassesState = TypeState.forUnion(bb, classInitializedClassesState, TypeState.forExactType(bb, m.getDeclaringClass(), false));
            }
        }

        for(Pair<TypeFlow<?>, TypeFlow<?>> e : interflows.keySet())
        {
            if(e.getLeft() == null && allInstantiatedTypeFlows.contains(e.getRight()))
                interflows.put(e, TypeState.forSubtraction(bb, interflows.get(e), classInitializedClassesState));
        }

        return classInitializers;
    }

    private Set<AnalysisMethod> calcUnrootedMethods(PointsToAnalysis bb)
    {
        HashSet<AnalysisMethod> unrootedMethods = new HashSet<>();

        for(Pair<AnalysisElement, Executable> p : unrootRegistered)
        {
            AnalysisMethod m = bb.getMetaAccess().lookupJavaMethod(p.getRight());
            unrootedMethods.add(m);

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

        return unrootedMethods;
    }

    public synchronized void dump(PointsToAnalysis bb) throws java.io.IOException
    {
        Map<Integer, Integer> typeIdMap = makeDenseTypeIdMap(bb, bb.getAllInstantiatedTypeFlow().getState()::containsType);
        AnalysisType[] types = getRelevantTypes(bb, typeIdMap);
        fillTypeflowGateMethods();
        Set<AnalysisMethod> unrootedMethods = calcUnrootedMethods(bb);
        Map<AnalysisMethod, Integer> methodIdMap = makeDenseMethodMap(bb, AnalysisMethod::isReachable);
        AnalysisMethod[] methods = getRelevantMethods(bb, methodIdMap);


        HashSet<AnalysisMethod> classInitializers = accountClassInitializersToTypeInstantiation(bb);

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

            for(Pair<AnalysisMethod, AnalysisMethod> e : direct_invokes)
            {
                if(e.getLeft() == null && unrootedMethods.contains(e.getRight()))
                    continue;

                if(e.getLeft() == null && classInitializers.contains(e.getRight()))
                    continue;

                int src = e.getLeft() == null ? 0 : methodIdMap.get(e.getLeft());
                int dst = methodIdMap.get(e.getRight());

                b.putInt(src);
                b.putInt(dst);
                b.flip();
                c.write(b);
                b.flip();
            }
        }

        class IntTriple
        {
            final int a, b, c;

            public IntTriple(int a, int b, int c)
            {
                this.a = a;
                this.b = b;
                this.c = c;
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

        for(Map.Entry<Pair<TypeFlow<?>, TypeFlow<?>>, TypeState> entry : interflows.entrySet())
        {
            int typestate_id = typestate_to_id.computeIfAbsent(entry.getValue(), assignId);
            interflows_compact.add(new IntTriple(entry.getKey().getLeft() == null ? 0 : entry.getKey().getLeft().id(), entry.getKey().getRight().id(), typestate_id));
        }

        for(Map.Entry<Pair<TypeFlow<?>, AnalysisMethod>, TypeState> entry : virtual_invokes.entrySet())
        {
            int typestate_id = typestate_to_id.computeIfAbsent(entry.getValue(), assignId);
            virtual_invokes_compact.add(new IntTriple(entry.getKey().getLeft().id(), methodIdMap.get(entry.getKey().getRight()), typestate_id));
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

            for(AnalysisMethod method : typeflowGateMethods)
            {
                int mid = method == null ? 0 : methodIdMap.get(method);

                b.putInt(mid);
                b.flip();
                c.write(b);
                b.flip();
            }
        }
    }

    private static Map<Integer, Integer> makeDenseTypeIdMap(BigBang bb, Predicate<AnalysisType> shouldBeIncluded)
    {
        ArrayList<AnalysisType> typesInPreorder = new ArrayList<>();

        // Execute inorder-tree-traversal on subclass hierarchy in order to have hierarchy subtrees in one contiguous id range
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

        // Add interfaces at the end
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

    private static AnalysisType[] getRelevantTypes(PointsToAnalysis bb, Map<Integer, Integer> typeIdMap)
    {
        AnalysisType[] types = new AnalysisType[typeIdMap.size()];

        for(AnalysisType t : bb.getAllInstantiatedTypes())
            types[typeIdMap.get(t.getId())] = t;

        return types;
    }

    private static Map<AnalysisMethod, Integer> makeDenseMethodMap(BigBang bb, Predicate<AnalysisMethod> shouldBeIncluded)
    {
        HashMap<AnalysisMethod, Integer> idMap = new HashMap<>();

        var ref = new Object() {
            int newId = 1; // Method 0 is logical <root>
        };

        for(AnalysisMethod m : bb.getUniverse().getMethods())
            if(shouldBeIncluded.test(m))
                idMap.computeIfAbsent(m, key -> ref.newId++);

        return idMap;
    }

    private static AnalysisMethod[] getRelevantMethods(PointsToAnalysis bb, Map<AnalysisMethod, Integer> methodIdMap)
    {
        AnalysisMethod[] methods = new AnalysisMethod[methodIdMap.size()];

        for(Map.Entry<AnalysisMethod, Integer> e : methodIdMap.entrySet())
        {
            methods[e.getValue() - 1] = e.getKey();
        }

        return methods;
    }

    private void fillTypeflowGateMethods()
    {
        // Damit die Datei so groß wird wie es Typeflows gibt
        while(typeflowGateMethods.size() < typeflows.size())
            typeflowGateMethods.add(null);
    }
}
