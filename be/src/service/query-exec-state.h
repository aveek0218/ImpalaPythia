// Copyright 2013 Cloudera Inc.
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

#ifndef IMPALA_SERVICE_QUERY_EXEC_STATE_H
#define IMPALA_SERVICE_QUERY_EXEC_STATE_H

#include "common/status.h"
#include "exec/catalog-op-executor.h"
#include "util/runtime-profile.h"
#include "runtime/timestamp-value.h"
#include "service/child-query.h"
#include "statestore/query-schedule.h"
#include "gen-cpp/Frontend_types.h"
#include "service/impala-server.h"
#include "gen-cpp/Frontend_types.h"

#include <boost/thread.hpp>
#include <boost/unordered_set.hpp>
#include <vector>

namespace impala {

class ExecEnv;
class Coordinator;
class RuntimeState;
class RowBatch;
class Expr;
class TupleRow;
class Frontend;
class QueryExecStateCleaner;

// Execution state of a query. This captures everything necessary
// to convert row batches received by the coordinator into results
// we can return to the client. It also captures all state required for
// servicing query-related requests from the client.
// Thread safety: this class is generally not thread-safe, callers need to
// synchronize access explicitly via lock().
// To avoid deadlocks, the caller must *not* acquire query_exec_state_map_lock_
// while holding the exec state's lock.
// TODO: Consider renaming to RequestExecState for consistency.
// TODO: Compute stats is the only stmt that requires child queries. Once the
// CatalogService performs background stats gathering the concept of child queries
// will likely become obsolete. Remove all child-query related code from this class.
class ImpalaServer::QueryExecState {
 public:
  QueryExecState(const TQueryContext& query_ctxt, ExecEnv* exec_env, Frontend* frontend,
                 ImpalaServer* server,
                 boost::shared_ptr<ImpalaServer::SessionState> session);

  ~QueryExecState() {
  }

  // Initiates execution of a exec_request.
  // Non-blocking.
  // Must *not* be called with lock_ held.
  Status Exec(TExecRequest* exec_request);

  // Execute a HiveServer2 metadata operation
  // TODO: This is likely a superset of GetTableNames/GetDbNames. Coalesce these different
  // code paths.
  Status Exec(const TMetadataOpRequest& exec_request);

  // Call this to ensure that rows are ready when calling FetchRows().
  // Must be preceded by call to Exec(). Waits for all child queries to complete.
  Status Wait();

  // Return at most max_rows from the current batch. If the entire current batch has
  // been returned, fetch another batch first.
  // Caller needs to hold fetch_rows_lock_ and lock_.
  // Caller should verify that EOS has not be reached before calling.
  // Always calls coord()->Wait() prior to getting a batch.
  // Also updates query_state_/status_ in case of error.
  Status FetchRows(const int32_t max_rows, QueryResultSet* fetched_rows);

  // Update query state if the requested state isn't already obsolete.
  // Takes lock_.
  void UpdateQueryState(beeswax::QueryState::type query_state);

  // Update the query status and the "Query Status" summary profile string.
  // If current status is already != ok, no update is made (we preserve the first error)
  // If called with a non-ok argument, the expectation is that the query will be aborted
  // quickly.
  // Returns the status argument (so we can write
  // RETURN_IF_ERROR(UpdateQueryStatus(SomeOperation())).
  // Does not take lock_, but requires it: caller must ensure lock_
  // is taken before calling UpdateQueryStatus
  Status UpdateQueryStatus(const Status& status);

  // Sets state to EXCEPTION and cancels coordinator with the given cause.
  // Caller needs to hold lock_.
  // Does nothing if the query has reached EOS.
  void Cancel(const Status* cause = NULL);

  // This is called when the query is done (finished, cancelled, or failed).
  // Takes lock_: callers must not hold lock() before calling.
  void Done();

  ImpalaServer::SessionState* session() const { return session_.get(); }
  const std::string& connected_user() const { return query_ctxt_.session.connected_user; }
  const std::string& do_as_user() const { return session_->do_as_user; }
  TSessionType::type session_type() const { return query_ctxt_.session.session_type; }
  const TUniqueId& session_id() const { return query_ctxt_.session.session_id; }
  const std::string& default_db() const { return query_ctxt_.session.database; }
  bool eos() const { return eos_; }
  Coordinator* coord() const { return coord_.get(); }
  QuerySchedule* schedule() { return schedule_.get(); }
  int num_rows_fetched() const { return num_rows_fetched_; }
  bool returns_result_set() { return !result_metadata_.columns.empty(); }
  const TResultSetMetadata* result_metadata() { return &result_metadata_; }
  const TUniqueId& query_id() const { return query_id_; }
  const TExecRequest& exec_request() const { return exec_request_; }
  TStmtType::type stmt_type() const { return exec_request_.stmt_type; }
  TCatalogOpType::type catalog_op_type() const {
    return exec_request_.catalog_op_request.op_type;
  }
  TDdlType::type ddl_type() const {
    return exec_request_.catalog_op_request.ddl_params.ddl_type;
  }
  boost::mutex* lock() { return &lock_; }
  boost::mutex* fetch_rows_lock() { return &fetch_rows_lock_; }
  const beeswax::QueryState::type query_state() const { return query_state_; }
  void set_query_state(beeswax::QueryState::type state) { query_state_ = state; }
  const Status& query_status() const { return query_status_; }
  void set_result_metadata(const TResultSetMetadata& md) { result_metadata_ = md; }
  const RuntimeProfile& profile() const { return profile_; }
  const TimestampValue& start_time() const { return start_time_; }
  const TimestampValue& end_time() const { return end_time_; }
  const std::string& sql_stmt() const { return query_ctxt_.request.stmt; }

  inline int64_t last_active() const {
    boost::lock_guard<boost::mutex> l(expiration_data_lock_);
    return last_active_time_;
  }

  // Returns true if Impala is actively processing this query.
  inline bool is_active() const {
    boost::lock_guard<boost::mutex> l(expiration_data_lock_);
    return ref_count_ > 0;
  }

  RuntimeProfile::EventSequence* query_events() { return query_events_; }

 private:
  TUniqueId query_id_;
  const TQueryContext query_ctxt_;

  // Ensures single-threaded execution of FetchRows(). Callers of FetchRows() are
  // responsible for acquiring this lock. To avoid deadlocks, callers must not hold lock_
  // while acquiring this lock (since FetchRows() will release and re-acquire lock_ during
  // its execution).
  boost::mutex fetch_rows_lock_;

  // Protects last_active_time_ and ref_count_.
  // Must always be taken as the last lock, that is no other locks may be taken while
  // holding this lock.
  mutable boost::mutex expiration_data_lock_;
  int64_t last_active_time_;

  // ref_count_ > 0 if Impala is currently performing work on this query's behalf. Every
  // time a client instructs Impala to do work on behalf of this query, the ref count is
  // increased, and decreased once that work is completed.
  uint32_t ref_count_;

  boost::mutex lock_;  // protects all following fields
  ExecEnv* exec_env_;

  // Session that this query is from
  boost::shared_ptr<SessionState> session_;

  // Resource assignment determined by scheduler. Owned by obj_pool_.
  boost::scoped_ptr<QuerySchedule> schedule_;

  // not set for ddl queries, or queries with "limit 0"
  boost::scoped_ptr<Coordinator> coord_;

  // Runs statements that query or modify the catalog via the CatalogService.
  boost::scoped_ptr<CatalogOpExecutor> catalog_op_executor_;

  // Result set used for requests that return results and are not QUERY
  // statements. For example, EXPLAIN, LOAD, and SHOW use this.
  boost::scoped_ptr<std::vector<TResultRow> > request_result_set_;

  // local runtime_state_ in case we don't have a coord_
  boost::scoped_ptr<RuntimeState> local_runtime_state_;
  ObjectPool profile_pool_;

  // The QueryExecState builds three separate profiles.
  // * profile_ is the top-level profile which houses the other
  //   profiles, plus the query timeline
  // * summary_profile_ contains mostly static information about the
  //   query, including the query statement, the plan and the user who submitted it.
  // * server_profile_ tracks time spent inside the ImpalaServer,
  //   but not inside fragment execution, i.e. the time taken to
  //   register and set-up the query and for rows to be fetched.
  //
  // There's a fourth profile which is not built here (but is a
  // child of profile_); the execution profile which tracks the
  // actual fragment execution.
  RuntimeProfile profile_;
  RuntimeProfile server_profile_;
  RuntimeProfile summary_profile_;
  RuntimeProfile::Counter* row_materialization_timer_;

  // Tracks how long we are idle waiting for a client to fetch rows.
  RuntimeProfile::Counter* client_wait_timer_;
  // Timer to track idle time for the above counter.
  MonotonicStopWatch client_wait_sw_;

  RuntimeProfile::EventSequence* query_events_;
  std::vector<Expr*> output_exprs_;
  bool eos_;  // if true, there are no more rows to return
  beeswax::QueryState::type query_state_;
  Status query_status_;
  TExecRequest exec_request_;

  TResultSetMetadata result_metadata_; // metadata for select query
  RowBatch* current_batch_; // the current row batch; only applicable if coord is set
  int current_batch_row_; // number of rows fetched within the current batch
  int num_rows_fetched_; // number of rows fetched by client for the entire query

  // To get access to UpdateCatalog, LOAD, and DDL methods. Not owned.
  Frontend* frontend_;

  // The parent ImpalaServer; called to wait until the the impalad has processed a
  // catalog update request. Not owned.
  ImpalaServer* parent_server_;

  // Start/end time of the query
  TimestampValue start_time_, end_time_;

  // List of child queries to be executed on behalf of this query.
  std::vector<ChildQuery> child_queries_;

  // Thread to execute child_queries_ in and the resulting status. The status is OK iff
  // all child queries complete successfully. Otherwise, status contains the error of the
  // first child query that failed (child queries are executed serially and abort on the
  // first error).
  Status child_queries_status_;
  boost::scoped_ptr<Thread> child_queries_thread_;

  // Executes a local catalog operation (an operation that does not need to execute
  // against the catalog service). Includes USE, SHOW, DESCRIBE, and EXPLAIN statements.
  Status ExecLocalCatalogOp(const TCatalogOpRequest& catalog_op);

  // Updates last_active_time_ and ref_count_ to reflect that query is currently not doing
  // any work. Takes expiration_data_lock_.
  void MarkInactive();

  // Updates last_active_time_ and ref_count_ to reflect that query is currently being
  // actively processed. Takes expiration_data_lock_.
  void MarkActive();

  // Core logic of initiating a query or dml execution request.
  // Initiates execution of plan fragments, if there are any, and sets
  // up the output exprs for subsequent calls to FetchRows().
  // Also sets up profile and pre-execution counters.
  // Non-blocking.
  Status ExecQueryOrDmlRequest(const TQueryExecRequest& query_exec_request);

  // Core logic of executing a ddl statement. May internally initiate execution of
  // queries (e.g., compute stats) or dml (e.g., create table as select)
  Status ExecDdlRequest();

  // Executes a LOAD DATA
  Status ExecLoadDataRequest();

  // Core logic of FetchRows(). Does not update query_state_/status_.
  // Caller needs to hold fetch_rows_lock_ and lock_.
  Status FetchRowsInternal(const int32_t max_rows, QueryResultSet* fetched_rows);

  // Fetch the next row batch and store the results in current_batch_. Only called for
  // non-DDL / DML queries. current_batch_ is set to NULL if execution is complete or the
  // query was cancelled.
  // Caller needs to hold fetch_rows_lock_ and lock_. Blocks, during which time lock_ is
  // released.
  Status FetchNextBatch();

  // Evaluates 'output_exprs_' against 'row' and output the evaluated row in
  // 'result'. The values' scales (# of digits after decimal) are stored in 'scales'.
  // result and scales must have been resized to the number of columns before call.
  Status GetRowValue(TupleRow* row, std::vector<void*>* result, std::vector<int>* scales);

  // Gather and publish all required updates to the metastore
  Status UpdateCatalog();

  // Copies results into request_result_set_
  void SetResultSet(const std::vector<std::string>& results);

  // Sets the result set for a CREATE TABLE AS SELECT statement. The results will not be
  // ready until all BEs complete execution. This can be called as part of Wait(),
  // at which point results will be avilable.
  void SetCreateTableAsSelectResultSet();

  // Updates the metastore's table and column statistics based on the child-query results
  // of a compute stats command.
  // TODO: Unify the various ways that the Metastore is updated for DDL/DML.
  // For example, INSERT queries update partition metadata in UpdateCatalog() using a
  // TUpdateCatalogRequest, whereas our DDL uses a TCatalogOpRequest for very similar
  // purposes. Perhaps INSERT should use a TCatalogOpRequest as well.
  Status UpdateTableAndColumnStats();

  // Asynchronously executes all child_queries_ one by one. Calls ExecChildQueries()
  // in a new child_queries_thread_.
  void ExecChildQueriesAsync();

  // Serially executes the queries in child_queries_ by calling the child query's
  // ExecAndWait(). This function is blocking and is intended to be run in a separate
  // thread to ensure that Exec() remains non-blocking. Sets child_queries_status_.
  // Must not be called while holding lock_.
  void ExecChildQueries();

  // Waits for all child queries to complete successfully or with an error, by joining
  // child_queries_thread_. Returns a non-OK status if a child query fails or if the
  // parent query is cancelled (subsequent children will not be executed). Returns OK
  // if child_queries_thread_ is not set or if all child queries finished successfully.
  Status WaitForChildQueries();
};

}
#endif
