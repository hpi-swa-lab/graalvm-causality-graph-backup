package com.oracle.svm.hosted;

import com.oracle.graal.pointsto.reports.AnalysisReportsOptions;
import com.oracle.graal.pointsto.reports.HeapAssignmentTracing;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.option.HostedOptionValues;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.nativeimage.hosted.Feature;

import java.util.ArrayList;
import java.util.List;

@AutomaticallyRegisteredFeature
public class HeapAssignmentTracingFeature implements InternalFeature {
    @Override
    public List<Class<? extends Feature>> getRequiredFeatures() {
        // Add CausalityExporter as a dependency such that this onAnalysisExit()
        // is called after that of the CausalityExporter
        ArrayList<Class<? extends Feature>> a = new ArrayList<>();
        a.add(CausalityExporter.class);
        return a;
    }

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        OptionValues options = HostedOptionValues.singleton();
        Object heapAssignmentTracingAgentValue = AnalysisReportsOptions.HeapAssignmentTracingAgent.getValue(options);
        return heapAssignmentTracingAgentValue == null && AnalysisReportsOptions.PrintCausalityGraph.getValue(options)
                || heapAssignmentTracingAgentValue == Boolean.TRUE;
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        HeapAssignmentTracing.activate();
    }

    @Override
    public void onAnalysisExit(OnAnalysisExitAccess access) {
        HeapAssignmentTracing.getInstance().dispose();
    }
}
