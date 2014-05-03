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

import org.apache.log4j.Logger;

import com.cloudera.impala.authorization.Privilege;
import com.cloudera.impala.catalog.AuthorizationException;
import com.cloudera.impala.catalog.Column;
import com.cloudera.impala.catalog.HBaseTable;
import com.cloudera.impala.catalog.HdfsTable;
import com.cloudera.impala.catalog.PrimitiveType;
import com.cloudera.impala.catalog.Table;
import com.cloudera.impala.catalog.View;
import com.cloudera.impala.common.AnalysisException;
import com.cloudera.impala.thrift.TComputeStatsParams;
import com.cloudera.impala.thrift.TTableName;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

/**
 * Represents an COMPUTE STATS <table> statement for statistics collection. The
 * statement gathers all table and column stats for a given table and stores them in
 * the Metastore via the CatalogService. All existing stats for that table are replaced
 * and no existing stats are reused.
 *
 * TODO: Allow more coarse/fine grained (db, column) and/or incremental stats collection.
 */
public class ComputeStatsStmt extends StatementBase {
  private static final Logger LOG = Logger.getLogger(ComputeStatsStmt.class);

  protected final TableName tableName_;

  // Set during analysis.
  protected Table table_;

  // Query for getting the per-partition row count and the total row count.
  // Set during analysis.
  protected String tableStatsQueryStr_;

  // Query for getting the per-column NDVs and number of NULLs.
  // Set during analysis.
  protected String columnStatsQueryStr_;

  protected ComputeStatsStmt(TableName tableName) {
    Preconditions.checkState(tableName != null && !tableName.isEmpty());
    this.tableName_ = tableName;
    this.table_ = null;
  }

  @Override
  public void analyze(Analyzer analyzer) throws AnalysisException,
      AuthorizationException {
    table_ = analyzer.getTable(tableName_, Privilege.ALTER);
    if (table_ instanceof View) {
      throw new AnalysisException(String.format(
          "COMPUTE STATS not allowed on a view: %s", table_.getFullName()));
    }

    // Query for getting the per-partition row count and the total row count.
    StringBuilder tableStatsQueryBuilder = new StringBuilder("SELECT ");
    List<String> tableStatsSelectList = Lists.newArrayList();
    tableStatsSelectList.add("COUNT(*)");
    List<String> groupByCols = Lists.newArrayList();
    // Only add group by clause for HdfsTables.
    if (table_ instanceof HdfsTable) {
      for (int i = 0; i < table_.getNumClusteringCols(); ++i) {
        String colName = table_.getColumns().get(i).getName();
        groupByCols.add(colName);
        // For the select list, wrap the group by columns in a cast to string because
        // the Metastore stores them as strings.
        tableStatsSelectList.add("cast(" + colName + " as string)");
      }
    }
    tableStatsQueryBuilder.append(Joiner.on(", ").join(tableStatsSelectList));
    tableStatsQueryBuilder.append(" FROM " + table_.getFullName());
    if (!groupByCols.isEmpty()) {
      tableStatsQueryBuilder.append(" GROUP BY ");
      tableStatsQueryBuilder.append(Joiner.on(", ").join(groupByCols));
    }
    tableStatsQueryStr_ = tableStatsQueryBuilder.toString();
    LOG.debug(tableStatsQueryStr_);

    // Query for getting the per-column NDVs and number of NULLs.
    StringBuilder columnStatsQueryBuilder = new StringBuilder("SELECT ");
    List<String> columnStatsSelectList = Lists.newArrayList();
    // For Hdfs tables, exclude partition columns from stats gathering because Hive
    // cannot store them as part of the non-partition column stats. For HBase tables,
    // include the single clustering column (the row key).
    int startColIdx = (table_ instanceof HBaseTable) ? 0 : table_.getNumClusteringCols();
    for (int i = startColIdx; i < table_.getColumns().size(); ++i) {
      Column c = table_.getColumns().get(i);
      // NDV approximation function. Add explicit alias for later identification when
      // updating the Metastore.
      columnStatsSelectList.add("NDV(" + c.getName() + ") AS " + c.getName());
      // Count the number of NULL values.
      columnStatsSelectList.add("COUNT(IF(" + c.getName() + " IS NULL, 1, NULL))");
      // For STRING columns also compute the max and avg string length.
      if (c.getType().isStringType()) {
        columnStatsSelectList.add("MAX(length(" + c.getName() + "))");
        columnStatsSelectList.add("AVG(length(" + c.getName() + "))");
      } else {
        // For non-STRING columns use -1 as the max/avg length to avoid having to
        // treat STRING columns specially in the BE CatalogOpExecutor.
        columnStatsSelectList.add("CAST(-1 as INT)");
        columnStatsSelectList.add("CAST(-1 as DOUBLE)");
      }
    }
    columnStatsQueryBuilder.append(Joiner.on(", ").join(columnStatsSelectList));
    columnStatsQueryBuilder.append(" FROM " + table_.getFullName());
    columnStatsQueryStr_ = columnStatsQueryBuilder.toString();
    LOG.debug(columnStatsQueryStr_);
  }

  public String getTblStatsQuery() { return tableStatsQueryStr_; }
  public String getColStatsQuery() { return columnStatsQueryStr_; }

  @Override
  public String toSql() { return "COMPUTE STATS " + tableName_.toString(); }

  public TComputeStatsParams toThrift() {
    TComputeStatsParams params = new TComputeStatsParams();
    params.setTable_name(new TTableName(table_.getDb().getName(), table_.getName()));
    params.setTbl_stats_query(tableStatsQueryStr_);
    params.setCol_stats_query(columnStatsQueryStr_);
    return params;
  }
}
