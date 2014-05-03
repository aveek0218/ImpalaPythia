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

package com.cloudera.impala.planner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.analysis.Analyzer;
import com.cloudera.impala.analysis.Expr;
import com.cloudera.impala.analysis.TupleId;
import com.cloudera.impala.thrift.TExchangeNode;
import com.cloudera.impala.thrift.TExplainLevel;
import com.cloudera.impala.thrift.TPlanNode;
import com.cloudera.impala.thrift.TPlanNodeType;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * Receiver side of a 1:n data stream. Logically, an ExchangeNode consumes the data
 * produced by its children. For each of the sending child nodes the actual data
 * transmission is performed by the DataStreamSink of the PlanFragment housing
 * that child node. Typically, an ExchangeNode only has a single sender child but,
 * e.g., for distributed union queries an ExchangeNode may have one sender child per
 * union operand.
 *
 * TODO: merging of sorted inputs.
 */
public class ExchangeNode extends PlanNode {
  private final static Logger LOG = LoggerFactory.getLogger(ExchangeNode.class);

  public ExchangeNode(PlanNodeId id) {
    super(id, "EXCHANGE");
  }

  public void addChild(PlanNode node, boolean copyConjuncts) {
    // This ExchangeNode 'inherits' several parameters from its children.
    // Ensure that all children agree on them.
    if (!children_.isEmpty()) {
      Preconditions.checkState(limit_ == node.limit_);
      Preconditions.checkState(tupleIds_.equals(node.tupleIds_));
      Preconditions.checkState(rowTupleIds_.equals(node.rowTupleIds_));
      Preconditions.checkState(nullableTupleIds_.equals(node.nullableTupleIds_));
      Preconditions.checkState(compactData_ == node.compactData_);
    } else {
      limit_ = node.limit_;
      tupleIds_ = Lists.newArrayList(node.tupleIds_);
      rowTupleIds_ = Lists.newArrayList(node.rowTupleIds_);
      nullableTupleIds_ = Sets.newHashSet(node.nullableTupleIds_);
      compactData_ = node.compactData_;
    }
    if (copyConjuncts) conjuncts_.addAll(Expr.cloneList(node.conjuncts_, null));
    children_.add(node);
  }

  @Override
  public void addChild(PlanNode node) { addChild(node, false); }

  @Override
  public void setCompactData(boolean on) { this.compactData_ = on; }

  @Override
  public void computeStats(Analyzer analyzer) {
    Preconditions.checkState(!children_.isEmpty(),
        "ExchangeNode must have at least one child");
    cardinality_ = 0;
    for (PlanNode child: children_) {
      if (child.getCardinality() == -1) {
        cardinality_ = -1;
        break;
      }
      cardinality_ += child.getCardinality();
    }

    if (hasLimit()) {
      if (cardinality_ == -1) {
        cardinality_ = limit_;
      } else {
        cardinality_ = Math.min(limit_, cardinality_);
      }
    }

    // Pick the max numNodes_ and avgRowSize_ of all children.
    numNodes_ = Integer.MIN_VALUE;
    avgRowSize_ = Integer.MIN_VALUE;
    for (PlanNode child: children_) {
      numNodes_ = Math.max(child.numNodes_, numNodes_);
      avgRowSize_ = Math.max(child.avgRowSize_, avgRowSize_);
    }
  }

  @Override
  protected String getNodeExplainString(String prefix, String detailPrefix,
      TExplainLevel detailLevel) {
    StringBuilder output = new StringBuilder();
    output.append(String.format("%s%s:%s [%s]\n", prefix, id_.toString(), displayName_,
        getPartitionExplainString()));
    return output.toString();
  }

  public String getPartitionExplainString() {
    // For the non-fragmented explain levels, print the data partition
    // of the data stream sink that sends to this exchange node.
    Preconditions.checkState(!children_.isEmpty());
    DataSink sink = getChild(0).getFragment().getSink();
    if (sink == null) return "";
    Preconditions.checkState(sink instanceof DataStreamSink);
    DataStreamSink streamSink = (DataStreamSink) sink;
    if (!streamSink.getOutputPartition().isPartitioned() &&
        fragment_.isPartitioned()) {
      // If the output of the sink is not partitioned but the target fragment is
      // partitioned, then the data exchange is broadcast.
      return "BROADCAST";
    } else {
      return streamSink.getOutputPartition().getExplainString();
    }
  }

  @Override
  protected void toThrift(TPlanNode msg) {
    Preconditions.checkState(!children_.isEmpty(),
        "ExchangeNode must have at least one child");
    msg.node_type = TPlanNodeType.EXCHANGE_NODE;
    msg.exchange_node = new TExchangeNode();
    for (TupleId tid: tupleIds_) {
      msg.exchange_node.addToInput_row_tuples(tid.asInt());
    }
  }
}
