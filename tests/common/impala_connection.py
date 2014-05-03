#!/usr/bin/env python
# Copyright (c) 2012 Cloudera, Inc. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Common for connections to Impala. Currently supports Beeswax connections and
# in the future will support HS2 connections. Provides tracing around all
# operations.

from tests.beeswax.impala_beeswax import ImpalaBeeswaxClient, QueryResult
from thrift.transport.TSocket import TSocket
from thrift.protocol import TBinaryProtocol
from thrift.transport.TTransport import TBufferedTransport, TTransportException
from getpass import getuser

import abc
import logging
import os

LOG = logging.getLogger('impala_connection')
console_handler = logging.StreamHandler()
console_handler.setLevel(logging.INFO)
# All logging needs to be either executable SQL or a SQL comment (prefix with --).
console_handler.setFormatter(logging.Formatter('%(message)s'))
LOG.addHandler(console_handler)
LOG.propagate = False

# Common wrapper around the internal types of HS2/Beeswax operation/query handles.
class OperationHandle(object):
  def __init__(self, handle):
    self.__handle = handle

  def get_handle(self): return self.__handle


# Represents an Impala connection.
class ImpalaConnection(object):
  __metaclass__ = abc.ABCMeta

  @abc.abstractmethod
  def set_configuration_option(self, name, value):
    """Sets a configuraiton option name to the given value"""
    pass

  @abc.abstractmethod
  def get_configuration(self):
    """Returns the configuration (a dictionary of key-value pairs) for this connection"""
    pass

  @abc.abstractmethod
  def set_configuration(self, configuration_option_dict):
    """Replaces existing configuration with the given dictionary"""
    pass

  @abc.abstractmethod
  def clear_configuration(self):
    """Clears all existing configuration."""
    pass

  @abc.abstractmethod
  def connect(self):
    """Opens the connection"""
    pass

  @abc.abstractmethod
  def close(self):
    """Closes the connection. Can be called multiple times"""
    pass

  @abc.abstractmethod
  def get_state(self, operation_handle):
    """Returns the state of a query"""
    pass

  @abc.abstractmethod
  def get_log(self, operation_handle):
    """Returns the log of an operation"""
    pass

  @abc.abstractmethod
  def cancel(self, operation_handle):
    """Cancels an in-flight operation"""
    pass

  def execute(self, sql_stmt):
    """Executes a query and fetches the results"""
    pass

  @abc.abstractmethod
  def execute_async(self, sql_stmt):
    """Issues a query and returns the handle to the caller for processing"""
    pass

  @abc.abstractmethod
  def fetch(self, sql_stmt, operation_handle, batch_size=1024):
    """Fetches all query results given a handle and sql statement.
    TODO: Support fetching single batch"""
    pass


# Represents a connection to Impala using the Beeswax API.
class BeeswaxConnection(ImpalaConnection):
  def __init__(self, host_port, use_kerberos=False):
    self.__beeswax_client = ImpalaBeeswaxClient(host_port, use_kerberos)
    self.__host_port = host_port
    self.QUERY_STATES = self.__beeswax_client.query_states

  def set_configuration_option(self, name, value):
    # Only set the option if it's not already set to the same value.
    if self.__beeswax_client.get_query_option(name) != value:
      LOG.info('SET %s=%s;' % (name, value))
      self.__beeswax_client.set_query_option(name, value)

  def get_configuration(self):
    return self.__beeswax_client.get_query_options

  def set_configuration(self, config_option_dict):
    assert config_option_dict is not None, "config_option_dict cannot be None"
    self.clear_configuration()
    for name, value in config_option_dict.iteritems():
      self.set_configuration_option(name, value)

  def clear_configuration(self):
    self.__beeswax_client.clear_query_options()

  def connect(self):
    LOG.info("-- connecting to: %s" % self.__host_port)
    self.__beeswax_client.connect()

  def close(self):
    LOG.info("-- closing connection to: %s" % self.__host_port)
    self.__beeswax_client.close_connection()

  def execute(self, sql_stmt):
    LOG.info("-- executing against %s\n%s;\n" % (self.__host_port, sql_stmt))
    return self.__beeswax_client.execute(sql_stmt)

  def execute_async(self, sql_stmt):
    LOG.info("-- executing async: %s\n%s;\n" % (self.__host_port, sql_stmt))
    return OperationHandle(self.__beeswax_client.execute_query_async(sql_stmt))

  def cancel(self, operation_handle):
    LOG.info("-- canceling operation: %s" % operation_handle)
    return self.__beeswax_client.cancel_query(operation_handle.get_handle())

  def get_state(self, operation_handle):
    LOG.info("-- getting state for operation: %s" % operation_handle)
    return self.__beeswax_client.get_state(operation_handle.get_handle())

  def get_log(self, operation_handle):
    LOG.info("-- getting log for operation: %s" % operation_handle)
    return self.__beeswax_client.get_log(operation_handle.get_handle())

  def refresh(self):
    """Invalidate the Impalad catalog"""
    return self.execute("invalidate metadata")

  def refresh_table(self, db_name, table_name):
    """Refresh a specific table from the catalog"""
    return self.execute("refresh %s.%s" % (db_name, table_name))

  def fetch(self, sql_stmt, operation_handle):
    LOG.info("-- fetching results from: %s" % operation_handle)
    return self.__beeswax_client.fetch_results(sql_stmt, operation_handle.get_handle())

def create_connection(host_port, use_kerberos=False):
  # TODO: Support HS2 connections.
  return BeeswaxConnection(host_port=host_port, use_kerberos=use_kerberos)
