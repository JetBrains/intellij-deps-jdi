/*
 * Copyright (c) 1998, 2013, Oracle and/or its affiliates. All rights reserved.
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
 *
 * Copyright (C) 2019 JetBrains s.r.o.
 *
 * This program is free software; you can redistribute and/or modify it under
 * the terms of the GNU General Public License v2 with Classpath Exception.
 * The text of the license is available in the file LICENSE.TXT.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See LICENSE.TXT for more details.
 *
 * You may contact JetBrains s.r.o. at Na Hřebenech II 1718/10, 140 00 Prague,
 * Czech Republic or at legal@jetbrains.com.
 */

package build.tools.jdwpgen;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

class ConstantNode extends AbstractCommandNode {

    ConstantNode() {
        this(new ArrayList<>());
    }

    ConstantNode(List<Node> components) {
        this.kind = "Constant";
        this.components = components;
        this.lineno = 0;
    }

    void constrain(Context ctx) {
        if (components.size() != 0) {
            error("Constants have no internal structure");
        }
        super.constrain(ctx);
    }

    void genJava(PrintWriter writer, int depth) {
        indent(writer, depth);
        if (parent instanceof AbstractNamedNode) {
            if ("Error".equals(((AbstractNamedNode)parent).name())) {
                writer.print("public ");
            }
        }
        writer.println("static final int " + name + " = " +
                       nameNode.value() + ";");
    }

    void document(PrintWriter writer) {
        //Add anchor to each constant with format <constant table name>_<constant name>
        if (!(parent instanceof AbstractNamedNode)) {
            error("Parent must be ConstantSetNode, but it's " + parent.getClass().getSimpleName());
        }
        String tableName = ((AbstractNamedNode)parent).name;
        writer.println("<tr>"
                        + "<th scope=\"row\">"
                            + "<span id=\"" + tableName + "_" + name + "\"></span>"
                            + name
                        + "<td class=\"centered\">" + nameNode.value()
                        + "<td>" + comment() + "&nbsp;"
                    + "</tr>");
    }

    public String getName(){

        if (name == null || name.length() == 0) {
            prune();
        }
        return name;
    }
}
