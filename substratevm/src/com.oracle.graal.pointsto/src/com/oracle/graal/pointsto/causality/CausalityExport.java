package com.oracle.graal.pointsto.causality;

import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.flow.MethodTypeFlow;
import com.oracle.graal.pointsto.flow.NullCheckTypeFlow;
import com.oracle.graal.pointsto.flow.OffsetStoreTypeFlow;
import com.oracle.graal.pointsto.flow.StoreFieldTypeFlow;
import com.oracle.graal.pointsto.flow.TypeFlow;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.typestate.MultiTypeState;
import com.oracle.graal.pointsto.typestate.TypeState;
import org.graalvm.compiler.graph.Edges;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

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

    public synchronized void dump(PointsToAnalysis bb) throws java.io.IOException
    {
        AnalysisType[] types;

        {
            int maxTypeId = -1;
            for (AnalysisType t : bb.getAllInstantiatedTypes())
                maxTypeId = Math.max(maxTypeId, t.getId());

            types = new AnalysisType[maxTypeId + 1];

            for (AnalysisType t : bb.getAllInstantiatedTypes())
                types[t.getId()] = t;
        }

        while(typeflow_methods.size() < typeflows.size())
            typeflow_methods.add(null);

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
                b.putInt(e.src);
                b.putInt(e.dst);
                b.flip();
                c.write(b);
                b.flip();
            }
        }

        try(FileOutputStream out = new FileOutputStream("direct_invokes.txt"))
        {
            try(PrintStream w = new PrintStream(out))
            {
                for(Edge e : direct_invokes)
                {
                    w.print('M');
                    w.print(e.src);
                    w.print("->M");
                    w.println(e.dst);
                }
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

            int stateIndex = 0;
            for(TypeState state : typestate_by_id)
            {
                b.clear();
                zero.clear();

                int typesCount = state.typesCount();

                if(typesCount == 0)
                {
                    assert zero.remaining() == bytesPerTypestate;
                    c.write(zero);
                }
                else if(typesCount == 1)
                {
                    int typeIndex = state.exactType().getId();

                    b.put(zero);
                    b.put(typeIndex / 8, (byte)(1 << (typeIndex % 8)));
                    b.flip();
                    assert b.remaining() == bytesPerTypestate;
                    c.write(b);
                }
                else
                {
                    MultiTypeState multiState = (MultiTypeState)state;
                    byte[] rawData = multiState.getRawData();
                    b.put(rawData);
                    zero.limit(b.remaining());
                    b.flip();
                    assert (b.remaining() + zero.remaining()) == bytesPerTypestate;
                    c.write(b);
                    c.write(zero);
                }

                assert c.position() == stateIndex * bytesPerTypestate;
                stateIndex++;
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
}
