/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.drill.exec.planner.physical.visitor;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.base.Preconditions;
import org.apache.drill.exec.planner.StarColumnHelper;
import org.apache.drill.exec.planner.physical.JoinPrel;
import org.apache.drill.exec.planner.physical.Prel;
import org.apache.drill.exec.planner.physical.ProjectAllowDupPrel;
import org.apache.drill.exec.planner.physical.ProjectPrel;
import org.apache.drill.exec.planner.physical.ScanPrel;
import org.apache.drill.exec.planner.physical.ScreenPrel;
import org.apache.drill.exec.planner.physical.WriterPrel;
import org.eigenbase.rel.RelNode;
import org.eigenbase.rel.rules.RemoveTrivialProjectRule;
import org.eigenbase.reltype.RelDataType;
import org.eigenbase.reltype.RelDataTypeField;
import org.eigenbase.rex.RexInputRef;
import org.eigenbase.rex.RexNode;
import org.eigenbase.rex.RexUtil;
import org.eigenbase.util.Pair;

import com.google.common.collect.Lists;

public class StarColumnConverter extends BasePrelVisitor<Prel, boolean[], RuntimeException>{

  private static StarColumnConverter INSTANCE = new StarColumnConverter();

  private static final AtomicLong tableNumber = new AtomicLong(0);

  public static Prel insertRenameProject(Prel root) {
    // Prefixing columns for columns expanded from star column :
    // Insert one project under screen (PUS) to remove prefix, and one project above scan (PAS) to add prefix.
    // PUS AND PAS are required, when
    //   Any non-SCAN prel produces regular column / expression AND star column,
    //   or multiple star columns.
    //   This is because we have to use prefix to distinguish columns expanded
    //   from star column, from those regular column referenced in the query.

    // We use an array of boolean to keep track this condition.
    boolean [] prefixedForStar = new boolean [1];
    prefixedForStar[0] = false;

    return root.accept(INSTANCE, prefixedForStar);
  }

  @Override
  public Prel visitScreen(ScreenPrel prel, boolean[] prefixedForStar) throws RuntimeException {
    return insertProjUnderScreen(prel, prefixedForStar, prel.getChild().getRowType());
  }

  @Override
  public Prel visitWriter(WriterPrel prel, boolean[] prefixedForStar) throws RuntimeException {
    Prel newPrel = insertProjUnderScreen(prel, prefixedForStar, prel.getChild().getRowType());

    prefixedForStar[0] = false;

    return newPrel;
  }

  // insert PUS: Project Under Screen, when necessary.
  private Prel insertProjUnderScreen(Prel prel, boolean[] prefixedForStar, RelDataType origRowType) {

    Prel child = ((Prel) prel.getInput(0)).accept(INSTANCE, prefixedForStar);

    ProjectPrel proj = null;

    if (prefixedForStar[0]) {
      List<RexNode> exprs = Lists.newArrayList();
      for (int i = 0; i < origRowType.getFieldCount(); i++) {
        RexNode expr = child.getCluster().getRexBuilder().makeInputRef(origRowType.getFieldList().get(i).getType(), i);
        exprs.add(expr);
      }

      RelDataType newRowType = RexUtil.createStructType(child.getCluster().getTypeFactory(), exprs, origRowType.getFieldNames());

      int fieldCount = prel.getRowType().isStruct()? prel.getRowType().getFieldCount():1;

      // Insert PUS : remove the prefix and keep the original field name.
      if (fieldCount > 1) { // // no point in allowing duplicates if we only have one column
        proj = new ProjectAllowDupPrel(child.getCluster(), child.getTraitSet(), child, exprs, newRowType);
      } else {
        proj = new ProjectPrel(child.getCluster(), child.getTraitSet(), child, exprs, newRowType);
      }

      List<RelNode> children = Lists.newArrayList();

      children.add(proj);
      return (Prel) prel.copy(prel.getTraitSet(), children);
    } else {
      return prel;
    }

  }

  @Override
  public Prel visitProject(ProjectPrel prel, boolean[] prefixedForStar) throws RuntimeException {
    ProjectPrel proj = (ProjectPrel) prel;

    // Require prefix rename : there exists other expression, in addition to a star column.
    if (!prefixedForStar[0]  // not set yet.
        && StarColumnHelper.containsStarColumnInProject(prel.getChild().getRowType(), proj.getProjects())
        && prel.getRowType().getFieldNames().size() > 1) {
      prefixedForStar[0] = true;
    }

    // For project, we need make sure that the project's field name is same as the input,
    // when the project expression is RexInPutRef, since we may insert a PAS which will
    // rename the projected fields.



    RelNode child = ((Prel) prel.getInput(0)).accept(INSTANCE, prefixedForStar);

    List<String> fieldNames = Lists.newArrayList();

    for (Pair<String, RexNode> pair : Pair.zip(prel.getRowType().getFieldNames(), proj.getProjects())) {
      if (pair.right instanceof RexInputRef) {
        String name = child.getRowType().getFieldNames().get(((RexInputRef) pair.right).getIndex());
        fieldNames.add(name);
      } else {
        fieldNames.add(pair.left);
      }
    }

    // Make sure the field names are unique : no allow of duplicate field names in a rowType.
    fieldNames = makeUniqueNames(fieldNames);

    RelDataType rowType = RexUtil.createStructType(prel.getCluster().getTypeFactory(), proj.getProjects(), fieldNames);

    ProjectPrel newProj = (ProjectPrel) proj.copy(proj.getTraitSet(), child, proj.getProjects(), rowType);

    if (RemoveTrivialProjectRule.isTrivial(newProj)) {
      return (Prel) child;
    } else {
      return newProj;
    }
  }

  @Override
  public Prel visitPrel(Prel prel, boolean [] prefixedForStar) throws RuntimeException {
    // Require prefix rename : there exists other expression, in addition to a star column.
    if (!prefixedForStar[0]  // not set yet.
        && StarColumnHelper.containsStarColumn(prel.getRowType())
        && prel.getRowType().getFieldNames().size() > 1) {
      prefixedForStar[0] = true;
    }

    List<RelNode> children = Lists.newArrayList();
    for (Prel child : prel) {
      child = child.accept(this, prefixedForStar);
      children.add(child);
    }

    return (Prel) prel.copy(prel.getTraitSet(), children);
  }

  @Override
  public Prel visitScan(ScanPrel scanPrel, boolean [] prefixedForStar) throws RuntimeException {
    if (StarColumnHelper.containsStarColumn(scanPrel.getRowType()) && prefixedForStar[0] ) {

      List<RexNode> exprs = Lists.newArrayList();

      for (RelDataTypeField field : scanPrel.getRowType().getFieldList()) {
        RexNode expr = scanPrel.getCluster().getRexBuilder().makeInputRef(field.getType(), field.getIndex());
        exprs.add(expr);
      }

      List<String> fieldNames = Lists.newArrayList();

      long tableId = tableNumber.getAndIncrement();

      for (String name : scanPrel.getRowType().getFieldNames()) {
        if (StarColumnHelper.isNonPrefixedStarColumn(name)) {
          fieldNames.add("T" +  tableId + StarColumnHelper.PREFIX_DELIMITER + name);  // Add prefix to * column.
        } else {
          fieldNames.add(name);  // Keep regular column as it is.
        }
      }
      RelDataType rowType = RexUtil.createStructType(scanPrel.getCluster().getTypeFactory(), exprs, fieldNames);

      // insert a PAS.
      ProjectPrel proj = new ProjectPrel(scanPrel.getCluster(), scanPrel.getTraitSet(), scanPrel, exprs, rowType);

      return proj;
    } else {
      return visitPrel(scanPrel, prefixedForStar);
    }
  }

  private List<String> makeUniqueNames(List<String> names) {

    // We have to search the set of original names, plus the set of unique names that will be used finally .
    // Eg : the original names : ( C1, C1, C10 )
    // There are two C1, we may rename C1 to C10, however, this new name will conflict with the original C10.
    // That means we should pick a different name that does not conflict with the original names, in additional
    // to make sure it's unique in the set of unique names.

    HashSet<String> uniqueNames = new HashSet<String>();
    HashSet<String> origNames = new HashSet<String>(names);

    List<String> newNames = Lists.newArrayList();

    for (String s : names) {
      if (uniqueNames.contains(s)) {
        for (int i = 0; ; i++ ) {
          s = s + i;
          if (! origNames.contains(s) && ! uniqueNames.contains(s)) {
            break;
          }
        }
      }
      uniqueNames.add(s);
      newNames.add(s);
    }

    return newNames;
  }

}
