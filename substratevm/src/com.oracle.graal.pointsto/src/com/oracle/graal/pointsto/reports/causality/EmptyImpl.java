package com.oracle.graal.pointsto.reports.causality;

import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.flow.AbstractVirtualInvokeTypeFlow;
import com.oracle.graal.pointsto.flow.MethodTypeFlow;
import com.oracle.graal.pointsto.flow.TypeFlow;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.reports.CausalityExport;
import com.oracle.graal.pointsto.typestate.TypeState;
import jdk.vm.ci.meta.JavaConstant;

/*
 Does nothing. Used when CausalityExport isn't enabled.
 */
public class EmptyImpl extends CausalityExport {
    @Override
    public void addTypeFlowEdge(TypeFlow<?> from, TypeFlow<?> to) {
    }

    @Override
    public void beginSaturationHappening() {
    }

    @Override
    public void endSaturationHappening() {
    }

    @Override
    public void registerTypesFlowing(PointsToAnalysis bb, Reason reason, TypeFlow<?> destination, TypeState types) {
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
    public void registerReasonRoot(Reason reason) {
    }

    @Override
    protected void beginAccountingRootRegistrationsTo(Reason reason) {
    }

    @Override
    protected void endAccountingRootRegistrationsTo(Reason reason) {
    }
}
