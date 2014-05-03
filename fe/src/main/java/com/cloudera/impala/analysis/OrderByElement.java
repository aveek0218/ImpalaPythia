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


/**
 * Combination of expr, ASC/DESC, and nulls ordering.
 */
class OrderByElement {
  private final Expr expr_;
  private final boolean isAsc_;
  // Represents the NULLs ordering specified: true when "NULLS FIRST", false when
  // "NULLS LAST", and null if not specified.
  private final Boolean nullsFirstParam_;

  /**
   * Constructs the OrderByElement.
   *
   * 'nullsFirstParam' should be true if "NULLS FIRST", false if "NULLS LAST", or null if
   * the NULLs order was not specified.
   */
  public OrderByElement(Expr expr, boolean isAsc, Boolean nullsFirstParam) {
    super();
    this.expr_ = expr;
    this.isAsc_ = isAsc;
    this.nullsFirstParam_ = nullsFirstParam;
  }

  public Expr getExpr() { return expr_; }
  public boolean getIsAsc() { return isAsc_; }
  public Boolean getNullsFirstParam() { return nullsFirstParam_; }

  public String toSql() {
    StringBuilder strBuilder = new StringBuilder();
    strBuilder.append(expr_.toSql());
    strBuilder.append(isAsc_ ? " ASC" : " DESC");
    // When ASC and NULLS LAST or DESC and NULLS FIRST, we do not print NULLS FIRST/LAST
    // because it is the default behavior and we want to avoid printing NULLS FIRST/LAST
    // whenever possible as it is incompatible with Hive (SQL compatibility with Hive is
    // important for views).
    if (nullsFirstParam_ != null) {
      if (isAsc_ && nullsFirstParam_) {
        // If ascending, nulls are last by default, so only add if nulls first.
        strBuilder.append(" NULLS FIRST");
      } else if (!isAsc_ && !nullsFirstParam_) {
        // If descending, nulls are first by default, so only add if nulls last.
        strBuilder.append(" NULLS LAST");
      }
    }
    return strBuilder.toString();
  }

  /**
   * Compute nullsFirst.
   *
   * @param nullsFirstParam True if "NULLS FIRST", false if "NULLS LAST", or null if
   *                        the NULLs order was not specified.
   * @param isAsc
   * @return Returns true if nulls are ordered first or false if nulls are ordered last.
   *         Independent of isAsc.
   */
  public static boolean nullsFirst(Boolean nullsFirstParam, boolean isAsc) {
    return nullsFirstParam == null ? !isAsc : nullsFirstParam;
  }
}
