package com.oracle.graal.pointsto.purge;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class PurgeMethods {
    public static class Options {
        @Option(help = "Specifies method names (in qualified java notation), that should be purged out of the analysis, as if their body was empty.")
        public static final OptionKey<String> PurgeMethodsFile = new OptionKey<>("");
    }

    private static List<String> findMethodNames(BigBang bb)
    {
        String path = Options.PurgeMethodsFile.getValue(bb.getOptions());
        try {
            return path.isEmpty() ? new ArrayList<>() : Files.readAllLines(Path.of(path));
        } catch(IOException ex) {
            return new ArrayList<>();
        }
    }

    final HashSet<String> toPurge;

    public PurgeMethods(BigBang bb)
    {
        toPurge = new HashSet<>(findMethodNames(bb));
    }

    public boolean purgeRequested(AnalysisMethod m)
    {
        return toPurge.contains(m.getQualifiedName());
    }
}
