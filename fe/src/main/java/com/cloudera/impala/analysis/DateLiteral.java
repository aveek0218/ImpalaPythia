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

import java.sql.Timestamp;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.cloudera.impala.common.AnalysisException;
import com.cloudera.impala.thrift.TDateLiteral;
import com.cloudera.impala.thrift.TExprNode;
import com.cloudera.impala.thrift.TExprNodeType;
import com.google.common.base.Preconditions;

class DateLiteral extends LiteralExpr {
  private static final List<SimpleDateFormat> formats_ =
      new ArrayList<SimpleDateFormat>();
  static {
    formats_.add(new SimpleDateFormat("yyyy-MM-dd"));
    formats_.add(new SimpleDateFormat("yyyy-MM-dd hh:mm:ss"));
    formats_.add(new SimpleDateFormat("yyyy-MM-dd hh:mm:ss.SSS"));
  }
  private SimpleDateFormat acceptedFormat_ = null;
  private final Timestamp value_;

  /**
   * C'tor takes string because a DateLiteral
   * can only be constructed by an implicit cast
   * Parsing will only succeed if all characters of
   * s are accepted.
   * @param s
   *          string representation of date
   * @param type
   *          desired type of date literal
   */
  public DateLiteral(String s, ColumnType type) throws AnalysisException {
    Preconditions.checkArgument(type.isDateType());
    Date date = null;
    ParsePosition pos = new ParsePosition(0);
    for (SimpleDateFormat format : formats_) {
      pos.setIndex(0);
      date = format.parse(s, pos);
      if (pos.getIndex() == s.length()) {
        acceptedFormat_ = format;
        break;
      }
    }
    if (acceptedFormat_ == null) {
      throw new AnalysisException("Unable to parse string '" + s + "' to date.");
    }
    this.value_ = new Timestamp(date.getTime());
    this.type_ = type;
  }

  @Override
  public boolean equals(Object obj) {
    if (!super.equals(obj)) {
      return false;
    }
    // dateFormat does not need to be compared
    // because dates originating from strings of different formats
    // are still comparable
    return ((DateLiteral) obj).value_ == value_;
  }

  @Override
  public String toSqlImpl() {
    return getStringValue();
  }

  @Override
  public String getStringValue() {
    return acceptedFormat_.format(value_);
  }

  @Override
  protected void toThrift(TExprNode msg) {
    msg.node_type = TExprNodeType.DATE_LITERAL;
    msg.date_literal = new TDateLiteral(value_.getTime());
  }

  public Timestamp getValue() { return value_; }

  @Override
  protected Expr uncheckedCastTo(ColumnType targetType) throws AnalysisException {
    // programmer error, we should never reach this state
    Preconditions.checkState(false);
    return this;
  }

  @Override
  public int compareTo(LiteralExpr o) {
    throw new IllegalStateException("DateLiteral is not supported.");
  }
}