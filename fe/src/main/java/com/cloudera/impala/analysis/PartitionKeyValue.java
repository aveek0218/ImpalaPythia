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

import com.cloudera.impala.catalog.AuthorizationException;
import com.cloudera.impala.common.AnalysisException;
import com.google.common.base.Preconditions;

/**
 * Representation of a single column:value element in the PARTITION (...) clause of an
 * insert or alter table statement.
 */
public class PartitionKeyValue {
  // Name of partitioning column.
  private final String colName_;
  // Value of partitioning column. Set to null for dynamic inserts.
  private final Expr value_;
  // Evaluation of value for static partition keys, null otherwise. Set in analyze().
  private LiteralExpr literalValue_;

  public PartitionKeyValue(String colName, Expr value) {
    this.colName_ = colName.toLowerCase();
    this.value_ = value;
  }

  public void analyze(Analyzer analyzer) throws AnalysisException,
      AuthorizationException {
    if (isStatic() && !value_.isConstant()) {
      throw new AnalysisException(
          String.format("Non-constant expressions are not supported " +
              "as static partition-key values in '%s'.", toString()));
    }
    if (value_ == null) return;
    value_.analyze(analyzer);
    literalValue_ = LiteralExpr.create(value_, analyzer.getQueryContext());
  }

  public String getColName() { return colName_; }
  public Expr getValue() { return value_; }
  public LiteralExpr getLiteralValue() { return literalValue_; }
  public boolean isDynamic() { return value_ == null; }
  public boolean isStatic() { return !isDynamic(); }

  @Override
  public String toString() {
    return isStatic() ? colName_ + "=" + value_.toSql() : colName_;
  }

  /**
   * Utility method that returns the string value for the given partition key. For
   * NULL values (a NullLiteral type) or empty literal values this will return the
   * given null partition key value.
   */
  public static String getPartitionKeyValueString(LiteralExpr literalValue,
      String nullPartitionKeyValue) {
    Preconditions.checkNotNull(literalValue);
    if (literalValue instanceof NullLiteral || literalValue.getStringValue().isEmpty()) {
      return nullPartitionKeyValue;
    }
    return literalValue.getStringValue();
  }
}
