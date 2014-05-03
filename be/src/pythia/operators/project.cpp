/*
 * Copyright 2011, Pythia authors (see AUTHORS file).
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the copyright holder nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

#include "operators.h"

#include <sstream>
using namespace std;

/** 
 * Parses a string \a s. 
 *
 * Function parses strings like "$x". It first throws away any characters
 * before the $ sign. Then it attempts to read an int, and returns. On failure,
 * -1 is returned.
 */
int parseInput(const string& s)
{
	size_t l = s.find('$');
	if (l == string::npos)
		return -1;
	string remainder = s.substr(l+1);

	int ret = -1;
	istringstream ss(remainder);
	ss >> ret;
	return ss ? ret : -1;
}

void Project::init(libconfig::Config& root, libconfig::Setting& cfg)
{
	libconfig::Setting& node = cfg["projection"];
	dbgassert(node.isList() || node.isArray());

	Schema& srcschema = nextOp->getOutSchema();

	for (int idx = 0; idx < node.getLength(); ++idx)
	{
		string projattrstr = node[idx];

		int projattr = parseInput(projattrstr);
		if (projattr <= -1)	// Couldn't parse input as number
			throw InvalidParameter();
		if (static_cast<unsigned int>(projattr) >= srcschema.columns()) // Input attribute doesn't exist.
			throw IllegalSchemaDeclarationException();

		projlist.push_back(projattr);
	}

	MapWrapper::init(root, cfg);
}

void Project::mapinit(Schema& schema)
{
	Schema& srcschema = nextOp->getOutSchema();
	for (unsigned int i=0; i<projlist.size(); ++i)
	{
		schema.add(srcschema.get(projlist[i]));
	}
}

/**
 * Copy specified attributes of the input tuple to the output.
 */
void Project::map(void* tuple, Page* out, Schema& schema) 
{
	Schema& srcschema = nextOp->getOutSchema();
	void* dest = out->allocateTuple();
	dbgassert(dest != NULL);

	for (unsigned int i=0; i<projlist.size(); ++i)
	{
		schema.writeData(dest, i, srcschema.calcOffset(tuple, projlist[i]));
	}
}
