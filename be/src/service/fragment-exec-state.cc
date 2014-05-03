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

#include "service/fragment-exec-state.h"

#include <sstream>

#include "codegen/llvm-codegen.h"
#include "rpc/thrift-util.h"

using namespace apache::thrift;
using namespace boost;
using namespace impala;
using namespace std;

Status ImpalaServer::FragmentExecState::UpdateStatus(const Status& status) {
  lock_guard<mutex> l(status_lock_);
  if (!status.ok() && exec_status_.ok()) exec_status_ = status;
  return exec_status_;
}

Status ImpalaServer::FragmentExecState::Cancel() {
  lock_guard<mutex> l(status_lock_);
  RETURN_IF_ERROR(exec_status_);
  executor_.Cancel();
  return Status::OK;
}

Status ImpalaServer::FragmentExecState::Prepare(
    const TExecPlanFragmentParams& exec_params) {
  exec_params_ = exec_params;
  RETURN_IF_ERROR(executor_.Prepare(exec_params));
  executor_.OptimizeLlvmModule();
  return Status::OK;
}

void ImpalaServer::FragmentExecState::Exec() {
  // Open() does the full execution, because all plan fragments have sinks
  executor_.Open();
  executor_.Close();
}

// There can only be one of these callbacks in-flight at any moment, because
// it is only invoked from the executor's reporting thread.
// Also, the reported status will always reflect the most recent execution status,
// including the final status when execution finishes.
void ImpalaServer::FragmentExecState::ReportStatusCb(
    const Status& status, RuntimeProfile* profile, bool done) {
  DCHECK(status.ok() || done);  // if !status.ok() => done
  Status exec_status = UpdateStatus(status);

  Status coord_status;
  ImpalaInternalServiceConnection coord(client_cache_, coord_hostport_, &coord_status);
  if (!coord_status.ok()) {
    stringstream s;
    s << "couldn't get a client for " << coord_hostport_;
    UpdateStatus(Status(TStatusCode::INTERNAL_ERROR, s.str()));
    return;
  }

  TReportExecStatusParams params;
  params.protocol_version = ImpalaInternalServiceVersion::V1;
  params.__set_query_id(query_id_);
  params.__set_backend_num(backend_num_);
  params.__set_fragment_instance_id(fragment_instance_id_);
  exec_status.SetTStatus(&params);
  params.__set_done(done);
  profile->ToThrift(&params.profile);
  params.__isset.profile = true;

  RuntimeState* runtime_state = executor_.runtime_state();
  DCHECK(runtime_state != NULL);
  // Only send updates to insert status if fragment is finished, the coordinator
  // waits until query execution is done to use them anyhow.
  if (done) {
    TInsertExecStatus insert_status;

    if (runtime_state->hdfs_files_to_move()->size() > 0) {
      insert_status.__set_files_to_move(*runtime_state->hdfs_files_to_move());
    }
    if (runtime_state->num_appended_rows()->size() > 0) {
      insert_status.__set_num_appended_rows(*runtime_state->num_appended_rows());
    }
    if (runtime_state->insert_stats()->size() > 0) {
      insert_status.__set_insert_stats(*runtime_state->insert_stats());
    }

    params.__set_insert_exec_status(insert_status);
  }

  // Send new errors to coordinator
  runtime_state->GetUnreportedErrors(&(params.error_log));
  params.__isset.error_log = (params.error_log.size() > 0);

  TReportExecStatusResult res;
  Status rpc_status;
  try {
    try {
      coord->ReportExecStatus(res, params);
    } catch (TTransportException& e) {
      VLOG_RPC << "Retrying ReportExecStatus: " << e.what();
      rpc_status = coord.Reopen();
      if (!rpc_status.ok()) {
        // we need to cancel the execution of this fragment
        UpdateStatus(rpc_status);
        executor_.Cancel();
        return;
      }
      coord->ReportExecStatus(res, params);
    }
    rpc_status = Status(res.status);
  } catch (TException& e) {
    stringstream msg;
    msg << "ReportExecStatus() to " << coord_hostport_ << " failed:\n" << e.what();
    VLOG_QUERY << msg.str();
    rpc_status = Status(TStatusCode::INTERNAL_ERROR, msg.str());
  }

  if (!rpc_status.ok()) {
    // we need to cancel the execution of this fragment
    UpdateStatus(rpc_status);
    executor_.Cancel();
  }
}
