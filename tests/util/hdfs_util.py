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
# Hdfs access utilities

from xml.etree.ElementTree import parse
from pywebhdfs.webhdfs import PyWebHdfsClient, errors
import getpass
import types

class HdfsConfig(object):
  """Reads an XML configuration file (produced by a mini-cluster) into a dictionary
  accessible via get()"""
  def __init__(self, filename):
    self.conf = {}
    tree = parse(filename)
    for property in tree.getroot().getiterator('property'):
      self.conf[property.find('name').text] = property.find('value').text

  def get(self, key):
    try:
      return self.conf[key]
    except KeyValue:
      return None

def get_hdfs_client_from_conf(conf):
  """Returns a new HTTP client for an HDFS cluster using an HdfsConfig object"""
  hostport = conf.get('dfs.namenode.http-address')
  if hostport is None:
    raise Exception("dfs.namenode.http-address not found in config")

  host, port = hostport.split(":")
  return get_hdfs_client(host=host, port=port)

def __pyweb_hdfs_client_exists(self, path):
  """The PyWebHdfsClient doesn't provide an API to cleanly detect if a file or directory
  exists. This method is bound to each client that is created so tests can simply call
  hdfs_client.exists('path') and get back a bool.
  """
  try:
    self.get_file_dir_status(path)
  except errors.FileNotFound:
    return False
  return True

def get_hdfs_client(host, port, user_name=getpass.getuser()):
  """Returns a new HTTP client for an HDFS cluster using an explict host:port pair"""
  hdfs_client = PyWebHdfsClient(host=host, port=port, user_name=user_name)
  # Bind our "exists" method to hdfs_client.exists
  hdfs_client.exists = types.MethodType(__pyweb_hdfs_client_exists, hdfs_client)
  return hdfs_client
