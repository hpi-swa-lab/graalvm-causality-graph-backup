package com.oracle.graal.pointsto.reports.causality;

import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.flow.AbstractVirtualInvokeTypeFlow;
import com.oracle.graal.pointsto.flow.MethodTypeFlow;
import com.oracle.graal.pointsto.flow.TypeFlow;
import com.oracle.graal.pointsto.meta.AnalysisElement;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.reports.CausalityExport;
import com.oracle.graal.pointsto.typestate.TypeState;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaMethod;
import org.graalvm.nativeimage.hosted.Feature;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.util.function.Consumer;

/*
 Does nothing. Used when CausalityExport isn't enabled.
 */
public class EmptyImpl extends CausalityExport {

    @Override
    public void addTypeFlow(TypeFlow<?> flow) {
    }

    @Override
    public void addTypeFlowEdge(TypeFlow<?> from, TypeFlow<?> to) {
    }

    @Override
    public void setSaturationHappening(boolean currentlySaturating) {
    }

    @Override
    public void addTypeFlowFromHeap(PointsToAnalysis analysis, Class<?> creason, TypeFlow<?> fieldTypeFlow, AnalysisType flowingType) {
    }

    @Override
    public void addDirectInvoke(AnalysisMethod caller, AnalysisMethod callee) {
    }

    @Override
    public void addVirtualInvoke(PointsToAnalysis bb, AbstractVirtualInvokeTypeFlow invocation, AnalysisMethod concreteTargetMethod, TypeState concreteTargetMethodCallingTypes) {
    }

    @Override
    public void registerMethodFlow(MethodTypeFlow method) {
    }

    @Override
    public void registerVirtualInvokeTypeFlow(AbstractVirtualInvokeTypeFlow invocation) {
    }

    @Override
    public void registerTypeReachableRoot(AnalysisType type, boolean instantiated) {
    }

    @Override
    public void registerTypeReachableRoot(Class<?> type) {
    }

    @Override
    public void registerTypeReachableThroughHeap(AnalysisType type, JavaConstant object, boolean instantiated) {
    }

    @Override
    public void registerTypeReachableByMethod(AnalysisType type, JavaMethod m, boolean instantiated) {
    }

    @Override
    public void registerTypeInstantiated(PointsToAnalysis bb, TypeFlow<?> cause, AnalysisType type) {
    }

    @Override
    public void registerReachabilityNotification(AnalysisElement e, Consumer<Feature.DuringAnalysisAccess> callback) {
    }

    @Override
    public void registerNotificationStart(Consumer<Feature.DuringAnalysisAccess> notification) {
    }

    @Override
    public void registerNotificationEnd(Consumer<Feature.DuringAnalysisAccess> notification) {
    }

    @Override
    public void registerReasonRoot(Object reason) {
    }

    @Override
    protected void beginAccountingRootRegistrationsTo(Object reason) {
    }

    @Override
    protected void endAccountingRootRegistrationsTo(Object reason) {
    }
}
