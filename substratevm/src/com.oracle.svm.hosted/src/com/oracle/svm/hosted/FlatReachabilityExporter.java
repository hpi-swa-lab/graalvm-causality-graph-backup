package com.oracle.svm.hosted;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.core.BuildArtifacts;
import com.oracle.svm.core.BuildArtifacts.ArtifactType;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.option.HostedOptionValues;
import jdk.vm.ci.meta.JavaField;
import jdk.vm.ci.meta.JavaMethod;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.MetaUtil;
import jdk.vm.ci.meta.Signature;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Stream;

@AutomaticallyRegisteredFeature
@SuppressWarnings("unused")
public class FlatReachabilityExporter implements InternalFeature {

    public final Path reachableTypesPath = NativeImageGenerator
            .generatedFiles(HostedOptionValues.singleton())
            .resolve("reachable_types.txt");

    public final Path instantiatedTypesPath = NativeImageGenerator
            .generatedFiles(HostedOptionValues.singleton())
            .resolve("instantiated_types.txt");

    public final Path reachableMethodsPath = NativeImageGenerator
            .generatedFiles(HostedOptionValues.singleton())
            .resolve("reachable_methods.txt");

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return SubstrateOptions.GenerateFlatReachability.getValue();
    }

    @Override
    public void afterAnalysis(AfterAnalysisAccess access) {
        FeatureImpl.AfterAnalysisAccessImpl accessImpl = (FeatureImpl.AfterAnalysisAccessImpl) access;

        try (
                BufferedWriter reachableTypes = new BufferedWriter(new FileWriter(reachableTypesPath.toFile()));
                BufferedWriter instantiatedTypes = new BufferedWriter(new FileWriter(instantiatedTypesPath.toFile()))
        ) {
            ArrayList<AnalysisType> types = new ArrayList<>(accessImpl.getUniverse().getTypes());
            types.sort(Comparator.comparing(FlatReachabilityExporter::stableTypeName));

            for (AnalysisType t : types) {
                if (t.isReachable()) {
                    reachableTypes.write(FlatReachabilityExporter.stableTypeName(t));
                    reachableTypes.newLine();
                }
                if (t.isInstantiated()) {
                    instantiatedTypes.write(FlatReachabilityExporter.stableTypeName(t));
                    instantiatedTypes.newLine();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        BuildArtifacts.singleton().add(ArtifactType.BUILD_INFO, reachableTypesPath);
        BuildArtifacts.singleton().add(ArtifactType.BUILD_INFO, instantiatedTypesPath);

        try (BufferedWriter methods = new BufferedWriter(new FileWriter(reachableMethodsPath.toFile()))) {
            Stream<String> names = accessImpl
                    .getUniverse()
                    .getMethods()
                    .stream()
                    .filter(AnalysisMethod::isReachable)
                    .map(FlatReachabilityExporter::stableMethodName)
                    .sorted();

            for(String name : (Iterable<String>) names::iterator) {
                methods.write(name);
                methods.newLine();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        BuildArtifacts.singleton().add(ArtifactType.BUILD_INFO, reachableMethodsPath);
    }

    // Hotfix until c322e16d4b406f9e6b54a6188d19ef0c3a8b4535 gets merged

    private static String stableTypeName(JavaType t) {
        return MetaUtil.internalNameToJava(t.getName(), true, false);
    }

    private static String stableFieldName(JavaField f) {
        return stableTypeName(f.getDeclaringClass()) + '.' + f.getName();
    }

    private static String stableMethodName(JavaMethod m) {
        StringBuilder sb = new StringBuilder();
        sb.append(stableTypeName(m.getDeclaringClass()));
        sb.append('.');
        sb.append(m.getName());
        sb.append('(');

        Signature sig = m.getSignature();
        for(int i = 0; i < sig.getParameterCount(false); i++) {
            if (i != 0) {
                sb.append(", ");
            }
            sb.append(stableTypeName(sig.getParameterType(i, null)));
        }

        sb.append(')');
        sb.append(':');
        sb.append(stableTypeName(sig.getReturnType(null)));

        return sb.toString();
    }
}
