/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.nodes;

import org.graalvm.compiler.graph.NodeClass;
import org.graalvm.compiler.nodeinfo.NodeInfo;
import org.graalvm.compiler.nodes.spi.LIRLowerable;
import org.graalvm.compiler.nodes.spi.NodeLIRBuilderTool;

import static org.graalvm.compiler.nodeinfo.InputType.Condition;
import static org.graalvm.compiler.nodeinfo.NodeSize.SIZE_1;

@NodeInfo(allowedUsageTypes = {Condition}, size = SIZE_1)
public abstract class LIRLowerableLogicNode extends LogicNode implements LIRLowerable {

    public static final NodeClass<LIRLowerableLogicNode> TYPE = NodeClass.create(LIRLowerableLogicNode.class);

    public LIRLowerableLogicNode(NodeClass<? extends LIRLowerableLogicNode> c) {
        super(c);
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        // Nothing to generate here, because this is handled specially by the emitBranch method in
        // the LIR builder.
    }
}
