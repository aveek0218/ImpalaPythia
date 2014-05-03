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

import com.cloudera.impala.common.AnalysisException;
import com.cloudera.impala.thrift.TExprNode;
import com.cloudera.impala.thrift.TExprNodeType;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

public class NullLiteral extends LiteralExpr {

  public NullLiteral() {
    type_ = ColumnType.NULL;
  }

  @Override
  public boolean equals(Object obj) {
    if (!super.equals(obj)) {
      return false;
    }
    return obj instanceof NullLiteral;
  }

  @Override
  public String toSqlImpl() {
    return getStringValue();
  }

  @Override
  public String debugString() {
    return Objects.toStringHelper(this).toString();
  }

  @Override
  public String getStringValue() {
    return "NULL";
  }

  @Override
  protected Expr uncheckedCastTo(ColumnType targetType) throws AnalysisException {
    Preconditions.checkState(targetType.isValid());
    type_ = targetType;
    return this;
  }

  @Override
  protected void toThrift(TExprNode msg) {
    msg.node_type = TExprNodeType.NULL_LITERAL;
  }

  @Override
  public int compareTo(LiteralExpr o) {
    if (!(o instanceof NullLiteral)) return -1;
    return 0;
  }
}
