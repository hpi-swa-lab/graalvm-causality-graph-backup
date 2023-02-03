/*
 * Copyright (c) 2012, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.pointsto.infrastructure;

import java.util.Objects;

import com.oracle.graal.pointsto.util.GraalAccess;

import jdk.vm.ci.meta.ResolvedJavaType;

public interface OriginalClassProvider {

    static Class<?> getJavaClass(ResolvedJavaType javaType) {
        Class<?> result;
        if (javaType instanceof OriginalClassProvider) {
            result = ((OriginalClassProvider) javaType).getJavaClass();
        } else {
            return GraalAccess.getOriginalSnippetReflection().originalClass(javaType);
        }

        /*
         * Currently, we do not support types at run time that have no matching java.lang.Class in
         * the image generator. So while there is no 1:1 mapping between JVMCI types and classes,
         * there needs to be some java.lang.Class for every JVMCI type. This class is also stored in
         * DynamicHub.hostedJavaClass.
         */
        return Objects.requireNonNull(result);
    }

    Class<?> getJavaClass();
}
