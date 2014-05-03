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

package com.cloudera.impala.catalog;

import java.util.HashMap;
import java.util.List;

import org.apache.log4j.Logger;

import com.cloudera.impala.analysis.FunctionName;
import com.cloudera.impala.thrift.TCatalogObjectType;
import com.cloudera.impala.thrift.TDatabase;
import com.cloudera.impala.thrift.TFunctionType;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

/**
 * Internal representation of db-related metadata. Owned by Catalog instance.
 * Not thread safe.
 *
 * The static initialisation method loadDb is the only way to construct a Db
 * object.
 *
 * Tables are stored in a map from the table name to the table object. They may
 * be loaded 'eagerly' at construction or 'lazily' on first reference.
 * Tables are accessed via getTable which may trigger a metadata read in two cases:
 *  * if the table has never been loaded
 *  * if the table loading failed on the previous attempt
 */
public class Db implements CatalogObject {
  private static final Logger LOG = Logger.getLogger(Db.class);
  private final Catalog parentCatalog_;
  private final TDatabase thriftDb_;
  private long catalogVersion_ = Catalog.INITIAL_CATALOG_VERSION;

  // Table metadata cache.
  private final CatalogObjectCache<Table> tableCache_;

  // All of the registered user functions. The key is the user facing name (e.g. "myUdf"),
  // and the values are all the overloaded variants (e.g. myUdf(double), myUdf(string))
  // This includes both UDFs and UDAs
  private final HashMap<String, List<Function>> functions_;

  public Db(String name, Catalog catalog) {
    thriftDb_ = new TDatabase(name);
    parentCatalog_ = catalog;
    tableCache_ = new CatalogObjectCache<Table>(TableLoader.createTableLoader(this));
    functions_ = new HashMap<String, List<Function>>();
  }

  /**
   * Creates a Db object with no tables based on the given TDatabase thrift struct.
   */
  public static Db fromTDatabase(TDatabase db, Catalog parentCatalog) {
    return new Db(db.getDb_name(), parentCatalog);
  }

  public TDatabase toThrift() { return thriftDb_; }
  public String getName() { return thriftDb_.getDb_name(); }
  public TCatalogObjectType getCatalogObjectType() {
    return TCatalogObjectType.DATABASE;
  }

  public List<String> getAllTableNames() {
    return tableCache_.getAllNames();
  }

  public boolean containsTable(String tableName) {
    return tableCache_.contains(tableName.toLowerCase());
  }

  /**
   * Returns the Table with the given name if present in the table cache or loads the
   * table if it does not already exist in the cache. Returns null if the table does not
   * exist in the cache or if there was an error loading the table metadata.
   * TODO: Should we bubble this exception up?
   */
  public Table getTable(String tblName) {
    return tableCache_.getOrLoad(tblName);
  }

  /**
   * Adds a table to the table cache.
   */
  public void addTable(Table table) {
    tableCache_.add(table);
  }

  /**
   * Adds a given table name to the table cache. The next call to refreshTable() or
   * getOrLoadTable() will trigger a metadata load.
   */
  public void addTableName(String tableName) {
    tableCache_.addName(tableName);
  }

  /**
   * Reloads the metadata for the given table name.
   */
  public Table reloadTable(String tableName) {
    return tableCache_.reload(tableName);
  }

  /**
   * Invalidates the table's metadata, forcing a reload on the next access. Does
   * not remove the table from table cache's name set.
   */
  public void invalidateTable(String tableName) {
    tableCache_.invalidate(tableName);
  }

  /**
   * Removes the table name and any cached metadata from the Table cache.
   */
  public Table removeTable(String tableName) {
    return tableCache_.remove(tableName.toLowerCase());
  }

  /**
   * Returns all the function signatures in this DB that match the specified
   * fuction type. If the function type is null, all function signatures are returned.
   */
  public List<String> getAllFunctionSignatures(TFunctionType type) {
    List<String> names = Lists.newArrayList();
    synchronized (functions_) {
      for (List<Function> fns: functions_.values()) {
        for (Function f: fns) {
          if (type == null || (type == TFunctionType.SCALAR && f instanceof Udf) ||
               type == TFunctionType.AGGREGATE && f instanceof Uda) {
            names.add(f.signatureString());
          }
        }
      }
    }
    return names;
  }

  /**
   * Returns the number of functions in this database.
   */
  public int numFunctions() {
    synchronized (functions_) {
      return functions_.size();
    }
  }

  /**
   * See comment in Catalog.
   */
  public boolean functionExists(FunctionName name) {
    synchronized (functions_) {
      return functions_.get(name.getFunction()) != null;
    }
  }

  /*
   * See comment in Catalog.
   */
  public Function getFunction(Function desc, Function.CompareMode mode) {
    synchronized (functions_) {
      List<Function> fns = functions_.get(desc.functionName());
      if (fns == null) return null;

      // First check for identical
      for (Function f: fns) {
        if (f.compare(desc, Function.CompareMode.IS_IDENTICAL)) return f;
      }
      if (mode == Function.CompareMode.IS_IDENTICAL) return null;

      // Next check for indistinguishable
      for (Function f: fns) {
        if (f.compare(desc, Function.CompareMode.IS_INDISTINGUISHABLE)) return f;
      }
      if (mode == Function.CompareMode.IS_INDISTINGUISHABLE) return null;

      // Finally check for is_subtype
      for (Function f: fns) {
        if (f.compare(desc, Function.CompareMode.IS_SUBTYPE)) return f;
      }
    }
    return null;
  }

  public Function getFunction(String signatureString) {
    synchronized (functions_) {
      for (List<Function> fns: functions_.values()) {
        for (Function f: fns) {
          if (f.signatureString().equals(signatureString)) return f;
        }
      }
    }
    return null;
  }

  /**
   * See comment in Catalog.
   */
  public boolean addFunction(Function fn) {
    // TODO: add this to persistent store
    synchronized (functions_) {
      if (getFunction(fn, Function.CompareMode.IS_INDISTINGUISHABLE) != null) {
        return false;
      }
      List<Function> fns = functions_.get(fn.functionName());
      if (fns == null) {
        fns = Lists.newArrayList();
        functions_.put(fn.functionName(), fns);
      }
      return fns.add(fn);
    }
  }

  /**
   * See comment in Catalog.
   */
  public Function removeFunction(Function desc) {
    // TODO: remove this from persistent store.
    synchronized (functions_) {
      Function fn = getFunction(desc, Function.CompareMode.IS_INDISTINGUISHABLE);
      if (fn == null) return null;
      List<Function> fns = functions_.get(desc.functionName());
      Preconditions.checkNotNull(fns);
      fns.remove(fn);
      if (fns.isEmpty()) functions_.remove(desc.functionName());
      return fn;
    }
  }

  /**
   * Removes a Function with the matching signature string. Returns the removed Function
   * if a Function was removed as a result of this call, null otherwise.
   * TODO: Move away from using signature strings and instead use Function IDs.
   */
  public Function removeFunction(String signatureStr) {
    synchronized (functions_) {
      Function targetFn = getFunction(signatureStr);
      if (targetFn != null) return removeFunction(targetFn);
    }
    return null;
  }

  /**
   * Returns a map of functionNames to list of (overloaded) functions with that name.
   */
  public HashMap<String, List<Function>> getAllFunctions() {
    return functions_;
  }

  @Override
  public long getCatalogVersion() { return catalogVersion_; }
  @Override
  public void setCatalogVersion(long newVersion) { catalogVersion_ = newVersion; }
  public Catalog getParentCatalog() { return parentCatalog_; }
}
