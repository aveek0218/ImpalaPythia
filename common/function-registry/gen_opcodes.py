#!/usr/bin/env python
# Copyright 2012 Cloudera Inc.
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

# This script generates the necessary files to coordinate function calls between the FE
# and BE. In the FE, this creates a mapping between function signature (Operation &
# Arguments) to an opcode. The opcode is a thrift enum which is passed to the backend.
# The backend has all the information from just the opcode and does not need to worry
# about type checking.
#
# This scripts pulls function metadata input from
#   - impala/common/function-registry/impala_functions.py (manually maintained)
#   - impala/common/function-registry/generated_functions.py (auto-generated metadata)
#
# This script will generate 4 outputs
#  1. Thrift enum for all the opcodes
#  - impala/fe/src/thrift/Opcodes.thrift
#  2. FE java operators (one per function, ignoring overloading)
#  - impala/fe/generated-sources/gen-java/com/cloudera/impala/opcode/FunctionOperater.java
#  3  Java registry setup (registering all the functions with signatures)
#  - impala/fe/generated-sources/gen-java/com/cloudera/impala/opcode/FunctionRegistry.java
#  4. BE registry setup (mapping opcodes to ComputeFunctions)
#  - impala/be/generated-sources/opcode/opcode-registry-init.cc
#
# TODO: version the registry on the FE and BE so we can identify if they are out of sync

import sys
import os
from string import Template
import impala_functions
import generated_functions

native_types = {
  'BOOLEAN'       : 'bool',
  'TINYINT'       : 'char',
  'SMALLINT'      : 'short',
  'INT'           : 'int',
  'BIGINT'        : 'long',
  'FLOAT'         : 'float',
  'DOUBLE'        : 'double',
  'STRING'        : 'StringValue',
  'TIMESTAMP'     : 'TimestampValue',
}

thrift_preamble = '\
// Copyright 2012 Cloudera Inc.\n\
//\n\
// Licensed under the Apache License, Version 2.0 (the "License");\n\
// you may not use this file except in compliance with the License.\n\
// You may obtain a copy of the License at\n\
//\n\
// http://www.apache.org/licenses/LICENSE-2.0\n\
//\n\
// Unless required by applicable law or agreed to in writing, software\n\
// distributed under the License is distributed on an "AS IS" BASIS,\n\
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n\
// See the License for the specific language governing permissions and\n\
// limitations under the License.\n\
\n\
// This is a generated file, DO NOT EDIT.\n\
// To add new functions, see impala/common/function-registry/gen_opcodes.py\n\
\n\
namespace cpp impala\n\
namespace java com.cloudera.impala.thrift\n\
\n\
enum TExprOpcode {\n'

thrift_epilogue = '\
}\n\
\n'

cc_registry_preamble = '\
// Copyright 2012 Cloudera Inc.\n\
//\n\
// Licensed under the Apache License, Version 2.0 (the "License");\n\
// you may not use this file except in compliance with the License.\n\
// You may obtain a copy of the License at\n\
//\n\
// http://www.apache.org/licenses/LICENSE-2.0\n\
//\n\
// Unless required by applicable law or agreed to in writing, software\n\
// distributed under the License is distributed on an "AS IS" BASIS,\n\
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n\
// See the License for the specific language governing permissions and\n\
// limitations under the License.\n\
\n\
// This is a generated file, DO NOT EDIT.\n\
// To add new functions, see impala/common/function-registry/gen_opcodes.py\n\
\n\
#include "exprs/opcode-registry.h"\n\
#include "exprs/expr.h"\n\
#include "exprs/compound-predicate.h"\n\
#include "exprs/like-predicate.h"\n\
#include "exprs/math-functions.h"\n\
#include "exprs/string-functions.h"\n\
#include "exprs/timestamp-functions.h"\n\
#include "exprs/conditional-functions.h"\n\
#include "exprs/udf-builtins.h"\n\
#include "exprs/utility-functions.h"\n\
#include "opcode/functions.h"\n\
\n\
using namespace boost::posix_time;\n\
using namespace boost::gregorian;\n\
\n\
namespace impala {\n\
\n\
void OpcodeRegistry::Init() {\n'

cc_registry_epilogue = '\
}\n\
\n\
}\n'

operator_file_preamble = '\
//  Copyright 2012 Cloudera Inc.\n\
// \n\
//  Licensed under the Apache License, Version 2.0 (the "License");\n\
//  you may not use this file except in compliance with the License.\n\
//  You may obtain a copy of the License at\n\
// \n\
//  http://www.apache.org/licenses/LICENSE-2.0\n\
// \n\
//  Unless required by applicable law or agreed to in writing, software\n\
//  distributed under the License is distributed on an "AS IS" BASIS,\n\
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n\
//  See the License for the specific language governing permissions and\n\
//  limitations under the License.\n\
\n\
// This is a generated file, DO NOT EDIT.\n\
// To add new functions, see impala/common/function-registry/gen_opcodes.py\n\
\n\
package com.cloudera.impala.opcode;\n\
\n\
public enum FunctionOperator {\n'

operator_file_epilogue = '\
}\n'

java_registry_preamble = '\
//  Copyright 2012 Cloudera Inc.\n\
// \n\
//  Licensed under the Apache License, Version 2.0 (the "License");\n\
//  you may not use this file except in compliance with the License.\n\
//  You may obtain a copy of the License at\n\
// \n\
//  http://www.apache.org/licenses/LICENSE-2.0\n\
// \n\
//  Unless required by applicable law or agreed to in writing, software\n\
//  distributed under the License is distributed on an "AS IS" BASIS,\n\
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n\
//  See the License for the specific language governing permissions and\n\
//  limitations under the License.\n\
\n\
// This is a generated file, DO NOT EDIT.\n\
// To add new functions, see impala/common/function-registry/gen_opcodes.py\n\
\n\
package com.cloudera.impala.opcode;\n\
\n\
import com.cloudera.impala.analysis.OpcodeRegistry;\n\
import com.cloudera.impala.analysis.ColumnType;\n\
import com.cloudera.impala.thrift.TExprOpcode;\n\
import com.google.common.base.Preconditions;\n\
\n\
public class FunctionRegistry { \n\
  public static void InitFunctions(OpcodeRegistry registry) { \n\
    boolean result = true;\n\
\n'

java_registry_epilogue = '\
    Preconditions.checkState(result); \n\
  }\n\
}\n'


def initialize_sub(op, return_type, arg_types):
  sub = {}
  java_args = "ColumnType." + return_type
  sub["fn_class"] = "GetValueFunctions"
  sub["fn_signature"] = op
  sub["num_args"] = len(arg_types)
  for idx in range(0, len(arg_types)):
    arg = arg_types[idx]
    sub["fn_signature"] += "_" + native_types[arg]
    sub["native_type" + repr(idx + 1)] = native_types[arg]
    java_args += ", ColumnType." + arg
  sub["thrift_enum"] = sub["fn_signature"].upper()
  sub["java_output"] = "FunctionOperator." + op.upper() + ", TExprOpcode." + sub["thrift_enum"]
  sub["java_output"] += ", " + java_args
  return sub

FE_PATH = "../../fe/generated-sources/gen-java/com/cloudera/impala/opcode/"
BE_PATH = "../../be/generated-sources/opcode/"
THRIFT_PATH = "../thrift/"

# This contains a list of all the opcodes that are built base on the
# function name from the input.  Inputs can have multiple signatures
# with the same function name and the opcode is mangled using the
# arg types.
opcodes = []

# This contains a list of all the function names (no overloading/mangling)
operators = []

# This is a mapping of operators to a list of function meta data entries
# Each meta data entry is itself a map to store all the meta data
#   - fn_name, ret_type, args, be_fn, sql_names
meta_data_entries = {}

# Read in the function and add it to the meta_data_entries map
def add_function(fn_meta_data, udf_interface):
  fn_name = fn_meta_data[0]

  entry = {}
  entry["fn_name"] = fn_name
  entry["ret_type"] = fn_meta_data[1]
  entry["args"] = fn_meta_data[2]
  entry["be_fn"] = fn_meta_data[3]
  entry["sql_names"] = fn_meta_data[4]
  if udf_interface:
    entry["symbol"] = fn_meta_data[5]
  else:
    entry["symbol"] = "<no symbol specified>"
  entry["udf_interface"] = udf_interface

  if fn_name in meta_data_entries:
    meta_data_entries[fn_name].append(entry)
  else:
    fn_list = [entry]
    meta_data_entries[fn_name] = fn_list
    operators.append(fn_name.upper())

# Iterate over entries in the meta_data_entries map and generate opcodes.  Some
# entries will have the same name at this stage, quality the name withe the
# signature  to generate unique enums.
# Resulting opcode list is sorted with INVALID_OPCODE at beginning and LAST_OPCODE
# at end.
def generate_opcodes():
  for fn in meta_data_entries:
    entries = meta_data_entries[fn]
    if len(entries) > 1:
      for entry in entries:
        opcode = fn.upper()
        for arg in entry["args"]:
          if arg == "...":
            opcode += "_" + 'VARARGS'
          else:
            opcode += "_" + native_types[arg].upper()
        opcodes.append(opcode)
        entry["opcode"] = opcode
    else:
      opcodes.append(fn.upper())
      entries[0]["opcode"] = fn.upper()
  opcodes.sort()
  opcodes.insert(0, 'INVALID_OPCODE')
  opcodes.append('LAST_OPCODE')

# Generates the BE registry init file that will add all the compute functions
# to the registry.  Outputs the generated-file to 'filename'
def generate_be_registry_init(filename):
  cc_registry_file = open(filename, "w")
  cc_registry_file.write(cc_registry_preamble)

  for fn in meta_data_entries:
    entries = meta_data_entries[fn]
    for entry in entries:
      opcode = entry["opcode"]
      be_fn = entry["be_fn"]
      symbol = entry["symbol"]
      # We generate two casts to work around GCC Bug 11407
      cc_output = 'TExprOpcode::%s, (void*)(Expr::ComputeFn)%s, "%s"' \
                  % (opcode, be_fn, symbol)
      cc_registry_file.write("  this->Add(%s);\n" % (cc_output))

  cc_registry_file.write(cc_registry_epilogue)
  cc_registry_file.close()

# Generates the FE registry init file that registers all the functions.  This file
# contains all the opcode->function signature mappings and all of the string->operator
# mappings for sql functions
def generate_fe_registry_init(filename):
  java_registry_file = open(filename, "w")
  java_registry_file.write(java_registry_preamble)

  for fn in meta_data_entries:
    entries = meta_data_entries[fn]
    for entry in entries:
      java_output = ""
      if entry["udf_interface"]:
        java_output += "true"
      else:
        java_output += "false"
      java_output += ", FunctionOperator." + fn.upper()
      java_output += ", TExprOpcode." + entry["opcode"]
      # Check the last entry for varargs indicator.
      if entry["args"] and entry["args"][-1] == "...":
        entry["args"].pop()
        java_output += ", true"
      else:
        java_output += ", false"
      java_output += ", ColumnType." + entry["ret_type"]
      for arg in entry["args"]:
        java_output += ", ColumnType." + arg
      java_registry_file.write("    result &= registry.add(%s);\n" % java_output)
  java_registry_file.write("\n")

  mappings = {}

  for fn in meta_data_entries:
    entries = meta_data_entries[fn]
    for entry in entries:
      for name in entry["sql_names"]:
        if name in mappings:
          if mappings[name] != fn.upper():
            print "Invalid mapping \"%s\" -> FunctionOperator.%s." % (name, mappings[name])
            print "There is already a mapping \"%s\" -> FunctionOperator.%s.\n" % (name, fn.upper())
            sys.exit(1)
          continue
        mappings[name] = fn.upper()
        java_output = "\"%s\", FunctionOperator.%s" % (name, fn.upper())
        java_registry_file.write("    result &= registry.addFunctionMapping(%s);\n" % java_output)
  java_registry_file.write("\n")

  java_registry_file.write(java_registry_epilogue)
  java_registry_file.close()

# Read the function metadata inputs
for function in impala_functions.functions:
  if len(function) != 5:
    print "Invalid function entry in impala_functions.py:\n\t" + repr(function)
    sys.exit(1)
  add_function(function, False)

for function in impala_functions.udf_functions:
  assert len(function) == 6, \
         "Invalid function entry in impala_functions.py:\n\t" + repr(function)
  add_function(function, True)

for function in generated_functions.functions:
  if len(function) != 5:
    print "Invalid function entry in generated_functions.py:\n\t" + repr(function)
    sys.exit(1)
  add_function(function, False)

generate_opcodes()

if not os.path.exists(BE_PATH):
  os.makedirs(BE_PATH)
if not os.path.exists(FE_PATH):
  os.makedirs(FE_PATH)
if not os.path.exists(THRIFT_PATH):
  os.makedirs(THRIFT_PATH)

generate_be_registry_init(BE_PATH + "opcode-registry-init.cc")
generate_fe_registry_init(FE_PATH + "FunctionRegistry.java")

# Output the opcodes to thrift
thrift_file = open(THRIFT_PATH + "Opcodes.thrift", "w")
thrift_file.write(thrift_preamble)
for opcode in opcodes:
  thrift_file.write("  %s,\n" % opcode)
thrift_file.write(thrift_epilogue)
thrift_file.close()

# Output the operators to java
operators.sort()
operators.insert(0, "INVALID_OPERATOR")
operator_java_file = open(FE_PATH + "FunctionOperator.java", "w")
operator_java_file.write(operator_file_preamble)
for op in operators:
  operator_java_file.write("  %s,\n" % op)
operator_java_file.write(operator_file_epilogue)
operator_java_file.close()
