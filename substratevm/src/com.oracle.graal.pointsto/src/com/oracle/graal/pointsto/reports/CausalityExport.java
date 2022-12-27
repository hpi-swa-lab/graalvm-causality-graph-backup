package com.oracle.graal.pointsto.reports;

import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.flow.AbstractVirtualInvokeTypeFlow;
import com.oracle.graal.pointsto.flow.MethodTypeFlow;
import com.oracle.graal.pointsto.flow.TypeFlow;
import com.oracle.graal.pointsto.meta.AnalysisElement;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.reports.causality.EmptyImpl;
import com.oracle.graal.pointsto.reports.causality.Impl;
import com.oracle.graal.pointsto.reports.causality.Graph;
import com.oracle.graal.pointsto.typestate.TypeState;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaMethod;
import org.graalvm.nativeimage.hosted.Feature;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public abstract class CausalityExport {
    private static final EmptyImpl dummyInstance = new EmptyImpl();
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
        return instance;
    }

    public static CausalityExport getInstance() {
        return instances != null ? instances.get() : dummyInstance;
    }


    // --- Registration ---
    public abstract void addTypeFlow(TypeFlow<?> flow);

    public abstract void addTypeFlowEdge(TypeFlow<?> from, TypeFlow<?> to);

    public abstract void setSaturationHappening(boolean currentlySaturating);

    public abstract void addTypeFlowFromHeap(PointsToAnalysis analysis, Class<?> creason, TypeFlow<?> fieldTypeFlow, AnalysisType flowingType);

    public abstract void addDirectInvoke(AnalysisMethod caller, AnalysisMethod callee);

    public abstract void addVirtualInvoke(PointsToAnalysis bb, AbstractVirtualInvokeTypeFlow invocation, AnalysisMethod concreteTargetMethod, TypeState concreteTargetMethodCallingTypes);

    public abstract void registerMethodFlow(MethodTypeFlow method);

    public abstract void registerVirtualInvokeTypeFlow(AbstractVirtualInvokeTypeFlow invocation);

    public abstract void registerTypeReachableRoot(AnalysisType type, boolean instantiated);

    public abstract void registerTypeReachableRoot(Class<?> type);

    public abstract void registerTypeReachableThroughHeap(AnalysisType type, JavaConstant object, boolean instantiated);

    public abstract void registerTypeReachableByMethod(AnalysisType type, JavaMethod m, boolean instantiated);

    public abstract void registerTypeInstantiated(PointsToAnalysis bb, TypeFlow<?> cause, AnalysisType type);

    public abstract void registerReachabilityNotification(AnalysisElement e, Consumer<Feature.DuringAnalysisAccess> callback);

    public abstract void registerNotificationStart(Consumer<Feature.DuringAnalysisAccess> notification);

    public abstract void registerNotificationEnd(Consumer<Feature.DuringAnalysisAccess> notification);

    public abstract void registerAnonymousRegistration(Executable e);

    public abstract void registerAnonymousRegistration(Field f);

    public abstract void registerAnonymousRegistration(Class<?> c);

    public synchronized void dump(PointsToAnalysis bb) throws java.io.IOException {
        Impl data = new Impl(instancesOfAllThreads, bb);
        Graph g = data.createCausalityGraph(bb);
        g.export(bb);
    }
}

