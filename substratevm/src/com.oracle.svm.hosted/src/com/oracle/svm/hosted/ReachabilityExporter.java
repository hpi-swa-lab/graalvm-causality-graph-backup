/*
 * Copyright (c) 2023, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted;

import java.io.IOException;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.oracle.svm.hosted.jni.JNIAccessFeature;
import org.graalvm.collections.EconomicMap;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.impl.RuntimeClassInitializationSupport;

import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.svm.core.BuildArtifacts;
import com.oracle.svm.core.BuildArtifacts.ArtifactType;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.option.HostedOptionValues;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.core.util.json.JsonWriter;
import com.oracle.svm.hosted.FeatureImpl.AfterCompilationAccessImpl;
import com.oracle.svm.hosted.classinitialization.ClassInitializationSupport;
import com.oracle.svm.hosted.classinitialization.InitKind;
import com.oracle.svm.hosted.code.CompileQueue.CompileTask;
import com.oracle.svm.hosted.meta.HostedField;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedType;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.reflect.ReflectionHostedSupport;

@AutomaticallyRegisteredFeature
@SuppressWarnings("unused")
public class ReachabilityExporter implements InternalFeature {

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return SubstrateOptions.GenerateReachabilityFile.getValue();
    }

    @Override
    public void afterCompilation(AfterCompilationAccess access) {
        AfterCompilationAccessImpl accessImpl = (AfterCompilationAccessImpl) access;
        HostedUniverse universe = accessImpl.getUniverse();
        Map<Class<?>, InitKind> classInitKinds = ((ClassInitializationSupport) ImageSingletons.lookup(RuntimeClassInitializationSupport.class)).getClassInitKinds();
        Map<HostedMethod, CompileTask> compilations = accessImpl.getCompilations();
        ReflectionHostedSupport reflectionHostedSupport = ImageSingletons.lookup(ReflectionHostedSupport.class);
        Map<AnalysisMethod, Executable> reflectionExecutables = reflectionHostedSupport.getReflectionExecutables();
        Map<AnalysisField, Field> reflectionFields = reflectionHostedSupport.getReflectionFields();
        JNIAccessFeature jniAccessFeature = JNIAccessFeature.singleton();
        Set<AnalysisMethod> jniMethods = Arrays.stream(jniAccessFeature.getRegisteredMethods()).map(universe.getBigBang().getUniverse()::lookup).collect(Collectors.toSet());

        Path buildPath = NativeImageGenerator.generatedFiles(HostedOptionValues.singleton());
        Path targetPath = buildPath.resolve(SubstrateOptions.REACHABILITY_FILE_NAME);
        EconomicMap<String, Object> map = EconomicMap.create();

        for (HostedType t : universe.getTypes()) {
            if(!t.getWrapped().isReachable())
                continue;

            if(t.isArray())
                continue;

            getTypeMap(map, t, classInitKinds);
        }
        for (HostedMethod m : universe.getMethods()) {
            if(!m.getWrapped().isReachable())
                continue;

            EconomicMap<String, Object> type = getTypeMap(map, m.getDeclaringClass(), classInitKinds);
            EconomicMap<String, Object> methods = getChild(type, "methods");
            String methodName = m.format("%n(%P)");
            EconomicMap<String, Object> methodMap = getChild(methods, methodName);
            propagateMethodDetails(methodMap, m, compilations, reflectionExecutables, jniMethods);
        }
        for (HostedField f : universe.getFields()) {
            if(!f.getWrapped().isReachable())
                continue;

            EconomicMap<String, Object> type = getTypeMap(map, f.getDeclaringClass(), classInitKinds);
            EconomicMap<String, Object> fields = getChild(type, "fields");
            EconomicMap<String, Object> fieldMap = getChild(fields, f.getName());
            propagateFieldDetails(fieldMap, f, reflectionFields);
        }

        try (JsonWriter writer = new JsonWriter(targetPath)) {
            writer.print(map);
            BuildArtifacts.singleton().add(ArtifactType.BUILD_INFO, targetPath);
        } catch (IOException e) {
            throw VMError.shouldNotReachHere("Unable to create " + SubstrateOptions.REACHABILITY_FILE_NAME, e);
        }
    }

    private static EconomicMap<String, Object> getTypeMap(EconomicMap<String, Object> map, HostedType type, Map<Class<?>, InitKind> classInitKinds) {
        String topLevelOriginName = findTopLevelOriginName(type.getJavaClass());
        EconomicMap<String, Object> topLevelOrigin = getChild(map, topLevelOriginName);
        EconomicMap<String, Object> modules = getChild(topLevelOrigin, "modules");
        String moduleName = findModuleName(type.getJavaClass());
        EconomicMap<String, Object> module = getChild(modules, moduleName);
        EconomicMap<String, Object> packages = getChild(module, "packages");
        EconomicMap<String, Object> _package = getChild(packages, type.getJavaClass().getPackageName());
        EconomicMap<String, Object> types = getChild(_package, "types");
        EconomicMap<String, Object> typeMap = getChild(types, type.getJavaClass().getSimpleName());
        if (typeMap.isEmpty()) {
            if(type.getWrapped().getWrapped().getClassInitializer() != null) {
                ArrayList<String> initKindList = new ArrayList<>();
                InitKind initKind = classInitKinds.get(type.getJavaClass());

                if (initKind != null) {
                    if (initKind == InitKind.RUN_TIME || initKind == InitKind.RERUN)
                        initKindList.add("run-time");
                    if (initKind == InitKind.BUILD_TIME || initKind == InitKind.RERUN)
                        initKindList.add("build-time");
                }

                typeMap.put("init-kind", initKindList.toArray());
            }
        }
        return typeMap;
    }

    private static void propagateMethodDetails(EconomicMap<String, Object> methodMap, HostedMethod m, Map<HostedMethod, CompileTask> compilations,
                    Map<AnalysisMethod, Executable> reflectionExecutables, Set<AnalysisMethod> jniMethods) {
        CompileTask compilation = compilations.get(m);
        int targetCodeSize = compilation != null ? compilation.result.getTargetCodeSize() : 0;
        methodMap.put("size", targetCodeSize);

        ArrayList<String> flagsList = new ArrayList<>();

        if(reflectionExecutables.containsKey(m.wrapped))
            flagsList.add("reflection");
        if(jniMethods.contains(m.wrapped))
            flagsList.add("jni");
        if(m.isSynthetic())
            flagsList.add("synthetic");

        if(!flagsList.isEmpty())
            methodMap.put("flags", flagsList.toArray());
    }

    private static void propagateFieldDetails(EconomicMap<String, Object> fieldMap, HostedField f, Map<AnalysisField, Field> reflectionFields) {
        ArrayList<String> flagsList = new ArrayList<>();

        if(reflectionFields.containsKey(f.wrapped))
            flagsList.add("reflection");
        if(f.wrapped.isJNIAccessed())
            flagsList.add("jni");
        if(f.isSynthetic())
            flagsList.add("synthetic");

        if(!flagsList.isEmpty())
            fieldMap.put("flags", flagsList.toArray());
    }

    @SuppressWarnings("unchecked")
    private static EconomicMap<String, Object> getChild(EconomicMap<String, Object> map, String key) {
        Object value = map.get(key);
        if (value != null) {
            return (EconomicMap<String, Object>) value;
        } else {
            EconomicMap<String, Object> childMap = EconomicMap.create();
            map.put(key, childMap);
            return childMap;
        }
    }

    private static String findTopLevelOriginName(Class<?> clazz) {
        CodeSource codeSource = clazz.getProtectionDomain().getCodeSource();
        if (codeSource != null && codeSource.getLocation() != null) {
            URL url = codeSource.getLocation();
            if(url.getProtocol().equals("file")) {
                return url.getPath();
            }
        }
        return "";
    }

    private static String findModuleName(Class<?> clazz) {
        String name = clazz.getModule().getName();
        return name != null ? name : "";
    }
}
