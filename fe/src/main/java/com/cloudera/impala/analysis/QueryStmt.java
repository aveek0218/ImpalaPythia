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

import java.util.ArrayList;
import java.util.List;

import com.cloudera.impala.catalog.AuthorizationException;
import com.cloudera.impala.common.AnalysisException;
import com.google.common.collect.Lists;

/**
 * Abstract base class for any statement that returns results
 * via a list of result expressions, for example a
 * SelectStmt or UnionStmt. Also maintains a map of expression substitutions
 * for replacing expressions from ORDER BY or GROUP BY clauses with
 * their corresponding result expressions.
 * Used for sharing members/methods and some of the analysis code, in particular the
 * analysis of the ORDER BY and LIMIT clauses.
 *
 */
public abstract class QueryStmt extends StatementBase {
  protected WithClause withClause_;

  protected ArrayList<OrderByElement> orderByElements_;
  protected final LimitElement limitElement_;

  // For a select statment:
  // original list of exprs in select clause (star-expanded, ordinals and
  // aliases substituted, agg output substituted)
  // For a union statement:
  // list of slotrefs into the tuple materialized by the union.
  protected ArrayList<Expr> resultExprs_ = Lists.newArrayList();

  // For a select statment: select list exprs resolved to base tbl refs
  // For a union statement: same as resultExprs
  protected ArrayList<Expr> baseTblResultExprs_ = Lists.newArrayList();

  /**
   * Map of expression substitutions for replacing aliases
   * in "order by" or "group by" clauses with their corresponding result expr.
   */
  protected final Expr.SubstitutionMap aliasSmap_ = new Expr.SubstitutionMap();

  /**
   * Select list item alias does not have to be unique.
   * This list contains all the non-unique aliases. For example,
   *   select int_col a, string_col a from alltypessmall;
   * Both columns are using the same alias "a".
   */
  protected final ArrayList<Expr> ambiguousAliasList_ = Lists.newArrayList();

  protected SortInfo sortInfo_;

  // True if this QueryStmt is the top level query from an EXPLAIN <query>
  protected boolean isExplain_ = false;

  // Analyzer that was used to analyze this query statement.
  protected Analyzer analyzer_;

  QueryStmt(ArrayList<OrderByElement> orderByElements, LimitElement limitElement) {
    this.orderByElements_ = orderByElements;
    this.sortInfo_ = null;
    this.limitElement_ =
        limitElement == null ? new LimitElement(null, null) : limitElement;
  }

  @Override
  public void analyze(Analyzer analyzer)
      throws AnalysisException, AuthorizationException {
    this.analyzer_ = analyzer;
    analyzeLimit(analyzer);
    if (hasWithClause()) withClause_.analyze(analyzer);
    analyzer.setIsExplain(isExplain_);
  }

  private void analyzeLimit(Analyzer analyzer) throws AnalysisException,
      AuthorizationException {
    if (limitElement_.getOffsetExpr() != null && !hasOrderByClause()) {
      throw new AnalysisException("OFFSET requires an ORDER BY clause: " + toSql());
    }
    limitElement_.analyze(analyzer);
  }


  /**
   * Creates sortInfo by resolving aliases and ordinals in the orderingExprs.
   */
  protected void createSortInfo(Analyzer analyzer) throws AnalysisException,
      AuthorizationException {
    // not computing order by
    if (orderByElements_ == null) return;

    ArrayList<Expr> orderingExprs = Lists.newArrayList();
    ArrayList<Boolean> isAscOrder = Lists.newArrayList();
    ArrayList<Boolean> nullsFirstParams = Lists.newArrayList();

    // extract exprs
    for (OrderByElement orderByElement: orderByElements_) {
      // create copies, we don't want to modify the original parse node, in case
      // we need to print it
      orderingExprs.add(orderByElement.getExpr().clone(null));
      isAscOrder.add(Boolean.valueOf(orderByElement.getIsAsc()));
      nullsFirstParams.add(orderByElement.getNullsFirstParam());
    }
    substituteOrdinals(orderingExprs, "ORDER BY");
    Expr ambiguousAlias = getFirstAmbiguousAlias(orderingExprs);
    if (ambiguousAlias != null) {
      throw new AnalysisException("Column " + ambiguousAlias.toSql() +
          " in order clause is ambiguous");
    }
    Expr.substituteList(orderingExprs, aliasSmap_);
    Expr.analyze(orderingExprs, analyzer);

    sortInfo_ = new SortInfo(orderingExprs, isAscOrder, nullsFirstParams);
  }

  /**
   * Return the first expr in exprs that is a non-unique alias. Return null if none of
   * exprs is an ambiguous alias.
   */
  protected Expr getFirstAmbiguousAlias(List<Expr> exprs) {
    for (Expr exp: exprs) {
      if (ambiguousAliasList_.contains(exp)) {
        return exp;
      }
    }
    return null;
  }

  /**
   * Substitute exprs of the form "<number>"  with the corresponding
   * expressions.
   */
  protected abstract void substituteOrdinals(List<Expr> exprs, String errorPrefix)
      throws AnalysisException;

  /**
   * UnionStmt and SelectStmt have different implementations.
   */
  public abstract ArrayList<String> getColLabels();

  /**
   * Returns the materialized tuple ids of the output of this stmt.
   * Used in case this stmt is part of an @InlineViewRef,
   * since we need to know the materialized tupls ids of a TableRef.
   * This call must be idempotent because it may be called more than once for Union stmt.
   */
  public abstract void getMaterializedTupleIds(ArrayList<TupleId> tupleIdList);

  public void setWithClause(WithClause withClause) { this.withClause_ = withClause; }
  public boolean hasWithClause() { return withClause_ != null; }
  public WithClause getWithClause() { return withClause_; }
  public ArrayList<OrderByElement> getOrderByElements() { return orderByElements_; }
  public void removeOrderByElements() { orderByElements_ = null; }
  public boolean hasOrderByClause() { return orderByElements_ != null; }
  public long getLimit() { return limitElement_.getLimit(); }
  public boolean hasLimitClause() { return limitElement_.getLimitExpr() != null; }
  public long getOffset() { return limitElement_.getOffset(); }
  public SortInfo getSortInfo() { return sortInfo_; }
  public ArrayList<Expr> getResultExprs() { return resultExprs_; }
  public ArrayList<Expr> getBaseTblResultExprs() { return baseTblResultExprs_; }
  public void setIsExplain(boolean isExplain) { this.isExplain_ = isExplain; }
  public boolean isExplain() { return isExplain_; }

  public void setResultExprs(List<Expr> resultExprs) {
    this.resultExprs_.clear();
    this.resultExprs_.addAll(resultExprs);
  }

  /**
   * Mark all slots that need to be materialized for the execution of this stmt.
   * This excludes slots referenced in resultExprs (it depends on the consumer of
   * the output of the stmt whether they'll be accessed) and single-table predicates
   * (the PlanNode that materializes that tuple can decide whether evaluating those
   * predicates requires slot materialization).
   * This is called prior to plan tree generation and allows tuple-materializing
   * PlanNodes to compute their tuple's mem layout.
   */
  public abstract void materializeRequiredSlots(Analyzer analyzer);

  /**
   * Mark slots referenced in exprs as materialized.
   */
  protected void materializeSlots(Analyzer analyzer, List<Expr> exprs) {
    List<SlotId> slotIds = Lists.newArrayList();
    for (Expr e: exprs) {
      e.getIds(null, slotIds);
    }
    analyzer.getDescTbl().markSlotsMaterialized(slotIds);
  }

  public ArrayList<OrderByElement> cloneOrderByElements() {
    return orderByElements_ != null ? Lists.newArrayList(orderByElements_) : null;
  }

  public WithClause cloneWithClause() {
    return withClause_ != null ? withClause_.clone() : null;
  }

  @Override
  public abstract QueryStmt clone();
}
