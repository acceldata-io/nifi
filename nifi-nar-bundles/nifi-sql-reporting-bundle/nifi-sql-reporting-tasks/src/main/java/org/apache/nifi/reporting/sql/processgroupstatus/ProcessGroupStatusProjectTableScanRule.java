/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.reporting.sql.processgroupstatus;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;

import java.util.List;

/**
 * Planner rule that projects from a {@link ProcessGroupStatusTableScan} scan just the columns
 * needed to satisfy a projection. If the projection's expressions are trivial,
 * the projection is removed.
 */
public class ProcessGroupStatusProjectTableScanRule extends RelOptRule {

    public ProcessGroupStatusProjectTableScanRule() {
        super(
            operand(LogicalProject.class,
                operand(ProcessGroupStatusTableScan.class, none())));
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        final LogicalProject project = call.rel(0);
        final ProcessGroupStatusTableScan scan = call.rel(1);
        final int[] fields = getProjectFields(project.getProjects());

        if (fields == null) {
            // Project contains expressions more complex than just field references.
            return;
        }

        call.transformTo(
            new ProcessGroupStatusTableScan(
                scan.getCluster(),
                scan.getTable(),
                scan.processGroupStatusTable,
                fields));
    }

    private int[] getProjectFields(List<RexNode> exps) {
        final int[] fields = new int[exps.size()];

        for (int i = 0; i < exps.size(); i++) {
            final RexNode exp = exps.get(i);

            if (exp instanceof RexInputRef) {
                fields[i] = ((RexInputRef) exp).getIndex();
            } else {
                return null; // not a simple projection
            }
        }

        return fields;
    }
}
