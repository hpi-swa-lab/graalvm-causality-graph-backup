package com.oracle.svm.hosted;

import com.oracle.graal.pointsto.meta.HostedProviders;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.annotation.CustomSubstitutionMethod;
import com.oracle.svm.hosted.phases.HostedGraphKit;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.StructuredGraph;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;

@AutomaticallyRegisteredFeature
@SuppressWarnings("unused")
public class MethodEviscerationFeature implements InternalFeature {
    private static HashSet<String> readLines(String path) {
        HashSet<String> lines = new HashSet<>();
        try (BufferedReader r = new BufferedReader(new FileReader(SubstrateOptions.EviscerateMethodsPath.getValue()))) {
            for(String line; (line = r.readLine()) != null;) {
                lines.add(line);
            }
        } catch (IOException e) {
            throw VMError.shouldNotReachHere(e);
        }
        return lines;
    }

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return SubstrateOptions.EviscerateMethodsPath.getValue() != null;
    }

    @Override
    public void duringSetup(DuringSetupAccess access) {
        var impl = (FeatureImpl.DuringSetupAccessImpl) access;
        impl.registerSubstitutionProcessor(new SubstitutionProcessor(readLines(SubstrateOptions.EviscerateMethodsPath.getValue())));
    }

    private static class SubstitutionProcessor extends com.oracle.graal.pointsto.infrastructure.SubstitutionProcessor {
        private final HashSet<String> victimNames;
        private final HashMap<ResolvedJavaMethod, EvisceratedMethod> cache = new HashMap<>();

        public SubstitutionProcessor(HashSet<String> victimNames) {
            this.victimNames = victimNames;
        }

        public ResolvedJavaMethod lookup(ResolvedJavaMethod method) {
            EvisceratedMethod subst = cache.get(method);
            if (subst != null)
                return subst;

            String name;
            try {
                name = FlatReachabilityExporter.stableMethodName(method);
            } catch(NullPointerException ex) {
                // Some CFunction-Stuff can't handle Signature the right way and throws on JavaMethod.format("%H.%n(%P)");
                return method;
            }

            if (!victimNames.contains(name)) {
                return method;
            }

            subst = new EvisceratedMethod(method);
            cache.put(method, subst);
            return subst;
        }

        public ResolvedJavaMethod resolve(ResolvedJavaMethod method) {
            return method instanceof EvisceratedMethod em ? em.getOriginal() : method;
        }
    }

    private static class EvisceratedMethod extends CustomSubstitutionMethod {
        public EvisceratedMethod(ResolvedJavaMethod original) {
            super(original);
        }

        @Override
        public int getModifiers() {
            return original.getModifiers() & ~(Modifier.SYNCHRONIZED | Modifier.NATIVE);
        }

        @Override
        public StructuredGraph buildGraph(DebugContext debug, ResolvedJavaMethod method, HostedProviders providers, Purpose purpose) {
            HostedGraphKit kit = new HostedGraphKit(debug, providers, method, purpose);
            JavaKind returnKind = original.getSignature().getReturnKind();
            kit.createReturn(returnKind == JavaKind.Void ? null : new ConstantNode(JavaConstant.defaultForKind(returnKind), StampFactory.forKind(returnKind)), returnKind);
            return kit.finalizeGraph();
        }
    }
}
