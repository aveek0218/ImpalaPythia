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

#include "rpc/thrift-client.h"

#include <boost/assign.hpp>
#include <ostream>

#include "util/time.h"

using namespace std;
using namespace boost;
using namespace apache::thrift::transport;

DECLARE_string(ssl_client_ca_certificate);

namespace impala {

Status ThriftClientImpl::Open() {
  if (!socket_create_status_.ok()) return socket_create_status_;
  try {
    if (!transport_->isOpen()) {
      transport_->open();
    }
  } catch (TTransportException& e) {
    stringstream msg;
    msg << "Couldn't open transport for " << ipaddress() << ":" << port()
        << "(" << e.what() << ")";
    return Status(msg.str());
  }
  return Status::OK;
}

Status ThriftClientImpl::OpenWithRetry(uint32_t num_tries, uint64_t wait_ms) {
  uint32_t try_count = 0L;
  while (true) {
    ++try_count;
    Status status = Open();
    if (status.ok()) return status;

    LOG(INFO) << "Unable to connect to " << ipaddress_ << ":" << port_;
    if (num_tries == 0) {
      LOG(INFO) << "(Attempt " << try_count << ", will retry indefinitely)";
    } else {
      if (num_tries != 1) {
        // No point logging 'attempt 1 of 1'
        LOG(INFO) << "(Attempt " << try_count << " of " << num_tries << ")";
      }
      if (try_count == num_tries) return status;
    }
    SleepForMs(wait_ms);
  }
}

void ThriftClientImpl::Close() {
  if (transport_.get() != NULL && transport_->isOpen()) transport_->close();
}

Status ThriftClientImpl::CreateSocket() {
  if (!ssl_) {
    socket_.reset(new TSocket(ipaddress_, port_));
  } else {
    try {
      TSSLSocketFactory factory;
      // TODO: No need to do this every time we create a socket, the factory can be
      // shared. But since there may be many certificates, this needs some slightly more
      // complex infrastructure to do right.
      factory.loadTrustedCertificates(FLAGS_ssl_client_ca_certificate.c_str());
      socket_ = factory.createSocket(ipaddress_, port_);
    } catch (const TTransportException& ex) {
      stringstream err_msg;
      err_msg << "Failed to create socket: " << ex.what();
      return Status(err_msg.str());
    }
  }

  return Status::OK;
}

}
