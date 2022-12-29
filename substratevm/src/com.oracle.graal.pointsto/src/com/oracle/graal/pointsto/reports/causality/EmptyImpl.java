package com.oracle.graal.pointsto.reports.causality;

import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.flow.AbstractVirtualInvokeTypeFlow;
import com.oracle.graal.pointsto.flow.ArrayElementsTypeFlow;
import com.oracle.graal.pointsto.flow.FieldTypeFlow;
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
import org.graalvm.nativeimage.hosted.Feature;

import java.util.function.Consumer;

/*
 Does nothing. Used when CausalityExport isn't enabled.
 */
public class EmptyImpl extends CausalityExport {
    @Override
    public void addTypeFlowEdge(TypeFlow<?> from, TypeFlow<?> to) {
    }

    @Override
    public void setSaturationHappening(boolean currentlySaturating) {
    }

    @Override
    public void registerTypesFlowing(PointsToAnalysis bb, Reason reason, TypeFlow<?> destination, TypeState types) {
    }

    @Override
    public void addDirectInvoke(AnalysisMethod caller, AnalysisMethod callee) {
    }

    @Override
    public void register(Reason reason, Reason consequence) {
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
    public Reason getReasonForHeapObject(PointsToAnalysis bb, JavaConstant heapObject) {
        return null;
    }

    @Override
    public Reason getReasonForHeapFieldAssignment(PointsToAnalysis analysis, JavaConstant receiver, AnalysisField field, JavaConstant value) {
        return null;
    }

    @Override
    public Reason getReasonForHeapArrayAssignment(PointsToAnalysis analysis, JavaConstant array, int elementIndex, JavaConstant value) {
        return null;
    }

    @Override
    public void registerReachabilityNotification(AnalysisElement e, Consumer<Feature.DuringAnalysisAccess> callback) {
    }

    @Override
    public void registerReasonRoot(Reason reason) {
    }

    @Override
    protected void beginAccountingRootRegistrationsTo(Reason reason) {
    }

    @Override
    protected void endAccountingRootRegistrationsTo(Reason reason) {
    }
}
