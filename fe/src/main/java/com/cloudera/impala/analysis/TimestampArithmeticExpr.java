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

import java.util.HashMap;
import java.util.Map;

import com.cloudera.impala.analysis.ArithmeticExpr.Operator;
import com.cloudera.impala.catalog.AuthorizationException;
import com.cloudera.impala.catalog.PrimitiveType;
import com.cloudera.impala.common.AnalysisException;
import com.cloudera.impala.opcode.FunctionOperator;
import com.cloudera.impala.thrift.TExprNode;
import com.cloudera.impala.thrift.TExprNodeType;
import com.google.common.base.Preconditions;

/**
 * Describes the addition and subtraction of time units from timestamps.
 * Arithmetic expressions on timestamps are syntactic sugar.
 * They are executed as function call exprs in the BE.
 */
public class TimestampArithmeticExpr extends Expr {

  // Time units supported in timestamp arithmetic.
  public static enum TimeUnit {
    YEAR("YEAR"),
    MONTH("MONTH"),
    WEEK("WEEK"),
    DAY("DAY"),
    HOUR("HOUR"),
    MINUTE("MINUTE"),
    SECOND("SECOND"),
    MILLISECOND("MILLISECOND"),
    MICROSECOND("MICROSECOND"),
    NANOSECOND("NANOSECOND");

    private final String description_;

    private TimeUnit(String description) {
      this.description_ = description;
    }

    @Override
    public String toString() {
      return description_;
    }
  }

  private static Map<String, TimeUnit> TIME_UNITS_MAP = new HashMap<String, TimeUnit>();
  static {
    for (TimeUnit timeUnit : TimeUnit.values()) {
      TIME_UNITS_MAP.put(timeUnit.toString(), timeUnit);
      TIME_UNITS_MAP.put(timeUnit.toString() + "S", timeUnit);
    }
  }

  // Set for function call-like arithmetic.
  private final String funcName_;
  private ArithmeticExpr.Operator op_;

  // Keep the original string passed in the c'tor to resolve
  // ambiguities with other uses of IDENT during query parsing.
  private final String timeUnitIdent_;
  private TimeUnit timeUnit_;

  // Indicates an expr where the interval comes first, e.g., 'interval b year + a'.
  private final boolean intervalFirst_;

  // C'tor for function-call like arithmetic, e.g., 'date_add(a, interval b year)'.
  public TimestampArithmeticExpr(String funcName, Expr e1, Expr e2,
      String timeUnitIdent) {
    this.funcName_ = funcName.toLowerCase();
    this.timeUnitIdent_ = timeUnitIdent;
    this.intervalFirst_ = false;
    children_.add(e1);
    children_.add(e2);
  }

  // C'tor for non-function-call like arithmetic, e.g., 'a + interval b year'.
  // e1 always refers to the timestamp to be added/subtracted from, and e2
  // to the time value (even in the interval-first case).
  public TimestampArithmeticExpr(ArithmeticExpr.Operator op, Expr e1, Expr e2,
      String timeUnitIdent, boolean intervalFirst) {
    Preconditions.checkState(op == Operator.ADD || op == Operator.SUBTRACT);
    this.funcName_ = null;
    this.op_ = op;
    this.timeUnitIdent_ = timeUnitIdent;
    this.intervalFirst_ = intervalFirst;
    children_.add(e1);
    children_.add(e2);
  }

  @Override
  public void analyze(Analyzer analyzer) throws AnalysisException,
      AuthorizationException {
    if (isAnalyzed_) return;
    super.analyze(analyzer);

    if (funcName_ != null) {
      // Set op based on funcName for function-call like version.
      if (funcName_.equals("date_add")) {
        op_ = ArithmeticExpr.Operator.ADD;
      } else if (funcName_.equals("date_sub")) {
        op_ = ArithmeticExpr.Operator.SUBTRACT;
      } else {
        throw new AnalysisException("Encountered function name '" + funcName_ +
            "' in timestamp arithmetic expression '" + toSql() + "'. " +
            "Expected function name 'DATE_ADD' or 'DATE_SUB'.");
      }
    }

    timeUnit_ = TIME_UNITS_MAP.get(timeUnitIdent_.toUpperCase());
    if (timeUnit_ == null) {
      throw new AnalysisException("Invalid time unit '" + timeUnitIdent_ +
          "' in timestamp arithmetic expression '" + toSql() + "'.");
    }

    // The first child must return a timestamp or null.
    if (getChild(0).getType().getPrimitiveType() != PrimitiveType.TIMESTAMP &&
        !getChild(0).getType().isNull()) {
      throw new AnalysisException("Operand '" + getChild(0).toSql() +
          "' of timestamp arithmetic expression '" + toSql() + "' returns type '" +
          getChild(0).getType() + "'. Expected type 'TIMESTAMP'.");
    }

    // The second child must be an integer type.
    if (!getChild(1).getType().isIntegerType() &&
        !getChild(1).getType().isNull()) {
      throw new AnalysisException("Operand '" + getChild(1).toSql() +
          "' of timestamp arithmetic expression '" + toSql() + "' returns type '" +
          getChild(1).getType() + "'. Expected an integer type.");
    }

    ColumnType[] argTypes = new ColumnType[this.children_.size()];
    for (int i = 0; i < this.children_.size(); ++i) {
      this.children_.get(i).analyze(analyzer);
      argTypes[i] = this.children_.get(i).getType();
    }
    String funcOpName = String.format("%sS_%s", timeUnit_.toString(),
        (op_ == ArithmeticExpr.Operator.ADD) ? "ADD" : "SUB");
    FunctionOperator funcOp =
        OpcodeRegistry.instance().getFunctionOperator(funcOpName);
    OpcodeRegistry.BuiltinFunction match =
        OpcodeRegistry.instance().getFunctionInfo(funcOp, true, argTypes);
    // We have already done type checking to ensure the function will resolve.
    Preconditions.checkNotNull(match);
    Preconditions.checkState(
        match.getReturnType().getPrimitiveType() == PrimitiveType.TIMESTAMP);
    opcode_ = match.opcode;
    type_ = match.getReturnType();
  }

  @Override
  protected void toThrift(TExprNode msg) {
    msg.node_type = TExprNodeType.COMPUTE_FUNCTION_CALL;
    msg.setOpcode(opcode_);
  }

  public String getTimeUnitIdent() { return timeUnitIdent_; }
  public TimeUnit getTimeUnit() { return timeUnit_; }
  public ArithmeticExpr.Operator getOp() { return op_; }

  @Override
  public String toSqlImpl() {
    StringBuilder strBuilder = new StringBuilder();
    if (funcName_ != null) {
      // Function-call like version.
      strBuilder.append(funcName_.toUpperCase() + "(");
      strBuilder.append(getChild(0).toSql() + ", ");
      strBuilder.append("INTERVAL ");
      strBuilder.append(getChild(1).toSql());
      strBuilder.append(" " + timeUnitIdent_);
      strBuilder.append(")");
      return strBuilder.toString();
    }
    if (intervalFirst_) {
      // Non-function-call like version with interval as first operand.
      strBuilder.append("INTERVAL ");
      strBuilder.append(getChild(1).toSql() + " ");
      strBuilder.append(timeUnitIdent_);
      strBuilder.append(" " + op_.toString() + " ");
      strBuilder.append(getChild(0).toSql());
    } else {
      // Non-function-call like version with interval as second operand.
      strBuilder.append(getChild(0).toSql());
      strBuilder.append(" " + op_.toString() + " ");
      strBuilder.append("INTERVAL ");
      strBuilder.append(getChild(1).toSql() + " ");
      strBuilder.append(timeUnitIdent_);
    }
    return strBuilder.toString();
  }
}
