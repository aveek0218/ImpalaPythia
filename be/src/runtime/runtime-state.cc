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

#include <string>
#include <sstream>

#include "common/logging.h"
#include <boost/algorithm/string/join.hpp>

#include "codegen/llvm-codegen.h"
#include "common/object-pool.h"
#include "common/status.h"
#include "exprs/expr.h"
#include "runtime/descriptors.h"
#include "runtime/disk-io-mgr.h"
#include "runtime/runtime-state.h"
#include "runtime/timestamp-value.h"
#include "runtime/data-stream-recvr.h"
#include "util/cpu-info.h"
#include "util/debug-util.h"
#include "util/disk-info.h"
#include "util/jni-util.h"
#include "util/mem-info.h"

#include <jni.h>
#include <iostream>

DECLARE_int32(max_errors);

using namespace boost;
using namespace llvm;
using namespace std;
using namespace boost::algorithm;

namespace impala {

RuntimeState::RuntimeState(const TUniqueId& query_id,
    const TUniqueId& fragment_instance_id, const TQueryContext& query_ctxt,
    const string& cgroup, ExecEnv* exec_env)
  : obj_pool_(new ObjectPool()),
    data_stream_recvrs_pool_(new ObjectPool()),
    unreported_error_idx_(0),
    query_ctxt_(query_ctxt),
    now_(new TimestampValue(query_ctxt.now_string.c_str(),
        query_ctxt.now_string.size())),
    query_id_(query_id),
    cgroup_(cgroup),
    profile_(obj_pool_.get(), "Fragment " + PrintId(fragment_instance_id)),
    is_cancelled_(false) {
  Status status = Init(fragment_instance_id, exec_env);
  DCHECK(status.ok()) << status.GetErrorMsg();
}

RuntimeState::RuntimeState(const TQueryContext& query_ctxt)
  : obj_pool_(new ObjectPool()),
    data_stream_recvrs_pool_(new ObjectPool()),
    unreported_error_idx_(0),
    query_ctxt_(query_ctxt),
    now_(new TimestampValue(query_ctxt.now_string.c_str(),
        query_ctxt.now_string.size())),
    exec_env_(ExecEnv::GetInstance()),
    profile_(obj_pool_.get(), "<unnamed>"),
    is_cancelled_(false) {
  query_ctxt_.request.query_options.__set_batch_size(DEFAULT_BATCH_SIZE);
}

RuntimeState::~RuntimeState() {
  if (udf_pool_.get() != NULL) udf_pool_->FreeAll();
  // query_mem_tracker_ must be valid as long as instance_mem_tracker_ is so
  // delete instance_mem_tracker_ first.
  instance_mem_tracker_.reset();
  query_mem_tracker_.reset();
}

Status RuntimeState::Init(const TUniqueId& fragment_instance_id, ExecEnv* exec_env) {
  fragment_instance_id_ = fragment_instance_id;
  exec_env_ = exec_env;
  TQueryOptions& query_options = query_ctxt_.request.query_options;
  if (!query_options.disable_codegen) {
    RETURN_IF_ERROR(CreateCodegen());
  } else {
    codegen_.reset(NULL);
  }
  if (query_options.max_errors <= 0) {
    // TODO: fix linker error and uncomment this
    //query_options_.max_errors = FLAGS_max_errors;
    query_options.max_errors = 100;
  }
  if (query_options.batch_size <= 0) {
    query_options.__set_batch_size(DEFAULT_BATCH_SIZE);
  }

  // Register with the thread mgr
  if (exec_env != NULL) {
    resource_pool_ = exec_env->thread_mgr()->RegisterPool();
    DCHECK(resource_pool_ != NULL);
  }

  total_cpu_timer_ = ADD_TIMER(runtime_profile(), "TotalCpuTime");
  total_storage_wait_timer_ = ADD_TIMER(runtime_profile(), "TotalStorageWaitTime");
  total_network_wait_timer_ = ADD_TIMER(runtime_profile(), "TotalNetworkWaitTime");

  return Status::OK;
}

Status RuntimeState::InitMemTrackers(const TUniqueId& query_id,
    int64_t query_bytes_limit) {
  query_mem_tracker_ =
      MemTracker::GetQueryMemTracker(
        query_id, query_bytes_limit, exec_env_->process_mem_tracker());
  instance_mem_tracker_.reset(new MemTracker(runtime_profile(), -1,
      runtime_profile()->name(), query_mem_tracker_.get()));
  if (query_bytes_limit != -1) {
    if (query_bytes_limit > MemInfo::physical_mem()) {
      LOG(WARNING) << "Memory limit "
                   << PrettyPrinter::Print(query_bytes_limit, TCounterType::BYTES)
                   << " exceeds physical memory of "
                   << PrettyPrinter::Print(MemInfo::physical_mem(), TCounterType::BYTES);
    }
    VLOG_QUERY << "Using query memory limit: "
               << PrettyPrinter::Print(query_bytes_limit, TCounterType::BYTES);
  }

  // TODO: this is a stopgap until we implement ExprContext
  udf_mem_tracker_.reset(
      new MemTracker(-1, "UDFs", instance_mem_tracker_.get()));
  udf_pool_.reset(new MemPool(udf_mem_tracker_.get()));
  return Status::OK;
}

DataStreamRecvr* RuntimeState::CreateRecvr(
    const RowDescriptor& row_desc, PlanNodeId dest_node_id, int num_senders,
    int buffer_size, RuntimeProfile* profile) {
  DataStreamRecvr* recvr = exec_env_->stream_mgr()->CreateRecvr(this, row_desc,
      fragment_instance_id_, dest_node_id, num_senders, buffer_size, profile);
  data_stream_recvrs_pool_->Add(recvr);
  return recvr;
}

void RuntimeState::set_now(const TimestampValue* now) {
  now_.reset(new TimestampValue(*now));
}

Status RuntimeState::CreateCodegen() {
  if (codegen_.get() != NULL) return Status::OK;
  RETURN_IF_ERROR(LlvmCodeGen::LoadImpalaIR(obj_pool_.get(), &codegen_));
  codegen_->EnableOptimizations(true);
  profile_.AddChild(codegen_->runtime_profile());
  return Status::OK;
}

bool RuntimeState::ErrorLogIsEmpty() {
  lock_guard<mutex> l(error_log_lock_);
  return (error_log_.size() > 0);
}

string RuntimeState::ErrorLog() {
  lock_guard<mutex> l(error_log_lock_);
  return join(error_log_, "\n");
}

string RuntimeState::FileErrors() const {
  lock_guard<mutex> l(file_errors_lock_);
  stringstream out;
  for (int i = 0; i < file_errors_.size(); ++i) {
    out << file_errors_[i].second << " errors in " << file_errors_[i].first << endl;
  }
  return out.str();
}

void RuntimeState::ReportFileErrors(const std::string& file_name, int num_errors) {
  lock_guard<mutex> l(file_errors_lock_);
  file_errors_.push_back(make_pair(file_name, num_errors));
}

bool RuntimeState::LogError(const string& error) {
  lock_guard<mutex> l(error_log_lock_);
  if (error_log_.size() < query_ctxt_.request.query_options.max_errors) {
    error_log_.push_back(error);
    return true;
  }
  return false;
}

void RuntimeState::LogError(const Status& status) {
  if (status.ok()) return;
  LogError(status.GetErrorMsg());
}

void RuntimeState::GetUnreportedErrors(vector<string>* new_errors) {
  lock_guard<mutex> l(error_log_lock_);
  if (unreported_error_idx_ < error_log_.size()) {
    new_errors->assign(error_log_.begin() + unreported_error_idx_, error_log_.end());
    unreported_error_idx_ = error_log_.size();
  }
}

Status RuntimeState::SetMemLimitExceeded(MemTracker* tracker,
    int64_t failed_allocation_size) {
  DCHECK_GE(failed_allocation_size, 0);
  {
    boost::lock_guard<boost::mutex> l(query_status_lock_);
    if (query_status_.ok()) {
      query_status_ = Status::MEM_LIMIT_EXCEEDED;
    } else {
      return query_status_;
    }
  }

  DCHECK(query_mem_tracker_.get() != NULL);
  stringstream ss;
  ss << "Memory Limit Exceeded\n";
  if (failed_allocation_size != 0) {
    DCHECK(tracker != NULL);
    ss << "  " << tracker->label() << " could not allocate "
       << PrettyPrinter::Print(failed_allocation_size, TCounterType::BYTES)
       << " without exceeding limit."
       << endl;
  }
  if (exec_env_->process_mem_tracker()->LimitExceeded()) {
    ss << exec_env_->process_mem_tracker()->LogUsage();
  } else {
    ss << query_mem_tracker_->LogUsage();
  }
  LogError(ss.str());
  DCHECK(query_status_.IsMemLimitExceeded());
  return query_status_;
}

Status RuntimeState::CheckQueryState() {
  // TODO: it would be nice if this also checked for cancellation, but doing so breaks
  // cases where we use Status::CANCELLED to indicate that the limit was reached.
  if (instance_mem_tracker_->AnyLimitExceeded()) return SetMemLimitExceeded();
  boost::lock_guard<boost::mutex> l(query_status_lock_);
  return query_status_;
}


}
