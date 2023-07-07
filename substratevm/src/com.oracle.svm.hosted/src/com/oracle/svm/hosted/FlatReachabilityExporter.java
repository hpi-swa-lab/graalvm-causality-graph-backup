package com.oracle.svm.hosted;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.core.BuildArtifacts;
import com.oracle.svm.core.BuildArtifacts.ArtifactType;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.hub.ClassForNameSupport;
import com.oracle.svm.core.option.HostedOptionValues;
import com.oracle.svm.hosted.jni.JNIAccessFeature;
import com.oracle.svm.hosted.reflect.ReflectionHostedSupport;
import jdk.vm.ci.meta.JavaField;
import jdk.vm.ci.meta.JavaMethod;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.MetaUtil;
import jdk.vm.ci.meta.Signature;
import org.graalvm.nativeimage.ImageSingletons;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
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

    public final Path reflectionAccessibleMethodsPath = NativeImageGenerator
            .generatedFiles(HostedOptionValues.singleton())
            .resolve("reflection_methods.txt");

    public final Path reflectionAccessibleTypesPath = NativeImageGenerator
            .generatedFiles(HostedOptionValues.singleton())
            .resolve("reflection_types.txt");

    public final Path jniAccessibleTypesPath = NativeImageGenerator
            .generatedFiles(HostedOptionValues.singleton())
            .resolve("jni_types.txt");
    public final Path jniAccessibleMethodsPath = NativeImageGenerator
            .generatedFiles(HostedOptionValues.singleton())
            .resolve("jni_methods.txt");

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return SubstrateOptions.GenerateFlatReachability.getValue();
    }

    private static void writeList(Path path, Stream<String> lines) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(path.toFile()))) {
            for(String line : (Iterable<String>) lines::iterator) {
                writer.write(line);
                writer.newLine();
            }
        }
        BuildArtifacts.singleton().add(ArtifactType.BUILD_INFO, path);
    }

    @Override
    public void afterAnalysis(AfterAnalysisAccess access) {
        FeatureImpl.AfterAnalysisAccessImpl accessImpl = (FeatureImpl.AfterAnalysisAccessImpl) access;

        try {
            writeList(reachableTypesPath,
                    accessImpl.getUniverse().getTypes().stream()
                            .filter(AnalysisType::isReachable)
                            .map(FlatReachabilityExporter::stableTypeName)
                            .sorted());
            writeList(instantiatedTypesPath,
                    accessImpl.getUniverse().getTypes().stream()
                            .filter(AnalysisType::isInstantiated)
                            .map(FlatReachabilityExporter::stableTypeName)
                            .sorted());
            writeList(reachableMethodsPath,
                    accessImpl
                            .getUniverse()
                            .getMethods()
                            .stream()
                            .filter(AnalysisMethod::isReachable)
                            .map(FlatReachabilityExporter::stableMethodName)
                            .sorted());
            writeList(reflectionAccessibleTypesPath,
                    Arrays.stream(ClassForNameSupport.getSuccessfullyRegisteredClasses())
                            .map(accessImpl.getBigBang().getMetaAccess()::optionalLookupJavaType)
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .filter(AnalysisType::isReachable)
                            .map(FlatReachabilityExporter::stableTypeName)
                            .sorted());
            writeList(reflectionAccessibleMethodsPath,
                    ImageSingletons.lookup(ReflectionHostedSupport.class).getReflectionExecutables().keySet().stream()
                            .filter(AnalysisMethod::isReachable)
                            .map(FlatReachabilityExporter::stableMethodName)
                            .sorted());
            writeList(jniAccessibleTypesPath,
                    Arrays.stream(JNIAccessFeature.singleton().getRegisteredClasses())
                            .map(accessImpl.getBigBang().getMetaAccess()::optionalLookupJavaType)
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .filter(AnalysisType::isReachable)
                            .map(FlatReachabilityExporter::stableTypeName)
                            .sorted());
            writeList(jniAccessibleMethodsPath,
                    Arrays.stream(JNIAccessFeature.singleton().getRegisteredMethods())
                            .map(accessImpl.getUniverse()::lookup)
                            .filter(AnalysisMethod::isReachable)
                            .map(FlatReachabilityExporter::stableMethodName)
                            .sorted());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // Hotfix until c322e16d4b406f9e6b54a6188d19ef0c3a8b4535 gets merged

    public static String stableTypeName(JavaType t) {
        return MetaUtil.internalNameToJava(t.getName(), true, false);
    }

    public static String stableFieldName(JavaField f) {
        return stableTypeName(f.getDeclaringClass()) + '.' + f.getName();
    }

    public static String stableMethodName(JavaMethod m) {
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
