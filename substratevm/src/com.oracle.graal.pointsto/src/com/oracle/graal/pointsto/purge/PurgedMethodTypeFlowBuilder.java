package com.oracle.graal.pointsto.purge;

import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.flow.MethodFlowsGraph;
import com.oracle.graal.pointsto.flow.MethodTypeFlowBuilder;
import com.oracle.graal.pointsto.meta.PointsToAnalysisMethod;

public class PurgedMethodTypeFlowBuilder extends MethodTypeFlowBuilder {
    public PurgedMethodTypeFlowBuilder(PointsToAnalysis bb, PointsToAnalysisMethod method, MethodFlowsGraph graph, MethodFlowsGraph.GraphKind graphKind) {
        super(bb, method, graph, graphKind);
    }

    @Override
    protected void apply(boolean forceReparse, Object reason) {
    }
}
