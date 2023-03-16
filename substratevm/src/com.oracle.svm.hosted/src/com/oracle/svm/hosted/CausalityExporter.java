package com.oracle.svm.hosted;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.reports.AnalysisReportsOptions;
import com.oracle.graal.pointsto.reports.CausalityExport;
import com.oracle.svm.core.BuildArtifacts;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.option.HostedOptionValues;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.core.util.json.JsonWriter;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.hosted.Feature;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@AutomaticallyRegisteredFeature
public class CausalityExporter implements InternalFeature {

    public final Path targetPath = NativeImageGenerator
            .generatedFiles(HostedOptionValues.singleton())
            .resolve("export.cg.zip");

    private ZipOutputStream zip;

    @Override
    public List<Class<? extends Feature>> getRequiredFeatures() {
        ArrayList<Class<? extends Feature>> a = new ArrayList<>();
        a.add(ReachabilityExporter.class);
        return a;
    }

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return AnalysisReportsOptions.PrintCausalityGraph.getValue(HostedOptionValues.singleton());
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        // The activation has to be done outside of this feature in order to be able to log feature registrations themselves.
    }

    @Override
    public void onAnalysisExit(OnAnalysisExitAccess access) {
        BigBang bb = ((FeatureImpl.OnAnalysisExitAccessImpl)access).bb;

        try {
            try {
                zip = new ZipOutputStream(new FileOutputStream(targetPath.toFile()));
                CausalityExport.dump((PointsToAnalysis) bb, zip);
            } catch(IOException ex) {
                if(zip != null) {
                    zip.close();
                    zip = null;
                }
                throw ex;
            }
        } catch (IOException ex) {
            throw VMError.shouldNotReachHere("Failed to create Causality Export", ex);
        }
    }

    @Override
    public void afterCompilation(AfterCompilationAccess access) {
        if(zip == null)
            return;

        ReachabilityExporter reachabilityExporter = ImageSingletons.lookup(ReachabilityExporter.class);

        try {
            zip.putNextEntry(new ZipEntry("reachability.json"));
            Files.copy(reachabilityExporter.reachabilityJsonPath, zip);
        } catch(IOException ex) {
            throw VMError.shouldNotReachHere("Failed to create Causality Export: reachability.json", ex);
        } finally {
            try {
                zip.close();
                zip = null;
            } catch(IOException ex) {
                throw VMError.shouldNotReachHere("Failed to close zip file", ex);
            }
        }

        BuildArtifacts.singleton().add(BuildArtifacts.ArtifactType.BUILD_INFO, targetPath);
    }
}
