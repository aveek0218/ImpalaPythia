// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.analysis;

import java.util.List;

import com.cloudera.impala.catalog.AuthorizationException;
import com.cloudera.impala.common.AnalysisException;
import com.cloudera.impala.opcode.FunctionOperator;
import com.cloudera.impala.thrift.TCaseExpr;
import com.cloudera.impala.thrift.TExprNode;
import com.cloudera.impala.thrift.TExprNodeType;
import com.google.common.base.Preconditions;

/**
 * CaseExpr represents the SQL expression
 * CASE [expr] WHEN expr THEN expr [WHEN expr THEN expr ...] [ELSE expr] END
 * Each When/Then is stored as two consecutive children (whenExpr, thenExpr).
 * If a case expr is given then it is the first child.
 * If an else expr is given then it is the last child.
 *
 */
public class CaseExpr extends Expr {
  private boolean hasCaseExpr_;
  private boolean hasElseExpr_;

  public CaseExpr(Expr caseExpr, List<CaseWhenClause> whenClauses, Expr elseExpr) {
    super();
    if (caseExpr != null) {
      children_.add(caseExpr);
      hasCaseExpr_ = true;
    }
    for (CaseWhenClause whenClause: whenClauses) {
      Preconditions.checkNotNull(whenClause.getWhenExpr());
      children_.add(whenClause.getWhenExpr());
      Preconditions.checkNotNull(whenClause.getThenExpr());
      children_.add(whenClause.getThenExpr());
    }
    if (elseExpr != null) {
      children_.add(elseExpr);
      hasElseExpr_ = true;
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (!super.equals(obj)) return false;
    CaseExpr expr = (CaseExpr) obj;
    return hasCaseExpr_ == expr.hasCaseExpr_ && hasElseExpr_ == expr.hasElseExpr_;
  }

  @Override
  public String toSqlImpl() {
    StringBuilder output = new StringBuilder("CASE");
    int childIdx = 0;
    if (hasCaseExpr_) {
      output.append(" " + children_.get(childIdx++).toSql());
    }
    while (childIdx + 2 <= children_.size()) {
      output.append(" WHEN " + children_.get(childIdx++).toSql());
      output.append(" THEN " + children_.get(childIdx++).toSql());
    }
    if (hasElseExpr_) {
      output.append(" ELSE " + children_.get(children_.size() - 1).toSql());
    }
    output.append(" END");
    return output.toString();
  }

  @Override
  protected void toThrift(TExprNode msg) {
    msg.node_type = TExprNodeType.CASE_EXPR;
    msg.case_expr = new TCaseExpr(hasCaseExpr_, hasElseExpr_);
    msg.setOpcode(opcode_);
  }

  @Override
  public void analyze(Analyzer analyzer) throws AnalysisException,
       AuthorizationException {
    if (isAnalyzed_) return;
    super.analyze(analyzer);

    // Keep track of maximum compatible type of case expr and all when exprs.
    ColumnType whenType = null;
    // Keep track of maximum compatible type of else expr and all then exprs.
    ColumnType returnType = null;
    // Remember last of these exprs for error reporting.
    Expr lastCompatibleThenExpr = null;
    Expr lastCompatibleWhenExpr = null;
    int loopEnd = children_.size();
    if (hasElseExpr_) {
      --loopEnd;
    }
    int loopStart;
    Expr caseExpr = null;
    // Set loop start, and initialize returnType as type of castExpr.
    if (hasCaseExpr_) {
      loopStart = 1;
      caseExpr = children_.get(0);
      caseExpr.analyze(analyzer);
      whenType = caseExpr.getType();
      lastCompatibleWhenExpr = children_.get(0);
    } else {
      whenType = ColumnType.BOOLEAN;
      loopStart = 0;
    }

    // Go through when/then exprs and determine compatible types.
    for (int i = loopStart; i < loopEnd; i += 2) {
      Expr whenExpr = children_.get(i);
      if (hasCaseExpr_) {
        // Determine maximum compatible type of the case expr,
        // and all when exprs seen so far. We will add casts to them at the very end.
        whenType = analyzer.getCompatibleType(whenType,
            lastCompatibleWhenExpr, whenExpr);
        lastCompatibleWhenExpr = whenExpr;
      } else {
        // If no case expr was given, then the when exprs should always return
        // boolean or be castable to boolean.
        if (!ColumnType.isImplicitlyCastable(whenExpr.getType(),
            ColumnType.BOOLEAN)) {
          throw new AnalysisException("When expr '" + whenExpr.toSql() + "'" +
              " is not of type boolean and not castable to type boolean.");
        }
        // Add a cast if necessary.
        if (!whenExpr.getType().isBoolean()) castChild(ColumnType.BOOLEAN, i);
      }
      // Determine maximum compatible type of the then exprs seen so far.
      // We will add casts to them at the very end.
      Expr thenExpr = children_.get(i + 1);
      returnType = analyzer.getCompatibleType(returnType,
          lastCompatibleThenExpr, thenExpr);
      lastCompatibleThenExpr = thenExpr;
    }
    if (hasElseExpr_) {
      Expr elseExpr = children_.get(children_.size() - 1);
      returnType = analyzer.getCompatibleType(returnType,
          lastCompatibleThenExpr, elseExpr);
    }

    // Add casts to case expr to compatible type.
    if (hasCaseExpr_) {
      // Cast case expr.
      if (!children_.get(0).type_.equals(whenType)) {
        castChild(whenType, 0);
      }
      // Add casts to when exprs to compatible type.
      for (int i = loopStart; i < loopEnd; i += 2) {
        if (!children_.get(i).type_.equals(whenType)) {
          castChild(whenType, i);
        }
      }
    }
    // Cast then exprs to compatible type.
    for (int i = loopStart + 1; i < children_.size(); i += 2) {
      if (!children_.get(i).type_.equals(returnType)) {
        castChild(returnType, i);
      }
    }
    // Cast else expr to compatible type.
    if (hasElseExpr_) {
      if (!children_.get(children_.size() - 1).type_.equals(returnType)) {
        castChild(returnType, children_.size() - 1);
      }
    }

    // Set opcode based on whenType.
    OpcodeRegistry.BuiltinFunction match = OpcodeRegistry.instance().getFunctionInfo(
        FunctionOperator.CASE, true, whenType);
    if (match == null) {
      throw new AnalysisException("Could not find match in function registry " +
          "for CASE and arg type: " + whenType);
    }
    opcode_ = match.opcode;
    type_ = returnType;
    isAnalyzed_ = true;
  }
}
