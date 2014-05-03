// Copyright (c) 2012 Cloudera, Inc. All rights reserved.

package com.cloudera.impala.testutil;
import static org.junit.Assert.fail;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import junit.framework.Assert;

import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.catalog.Catalog;
import com.cloudera.impala.catalog.PrimitiveType;
import com.cloudera.impala.thrift.TClientRequest;
import com.cloudera.impala.thrift.TInsertResult;
import com.cloudera.impala.thrift.TNetworkAddress;
import com.cloudera.impala.thrift.TQueryContext;
import com.cloudera.impala.thrift.TQueryOptions;
import com.cloudera.impala.thrift.TSessionState;
import com.cloudera.impala.thrift.TSessionType;
import com.cloudera.impala.thrift.TUniqueId;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

public class TestUtils {
  private final static Logger LOG = LoggerFactory.getLogger(TestUtils.class);
  private final static String[] expectedFilePrefix_ = { "hdfs:", "file: " };
  private final static String[] ignoreContentAfter_ = { "HOST:", "LOCATIONS:" };
  // Special prefix that designates an expected value specified as a regex rather
  // than a literal
  private final static String regexAgainstActual_ = "regex:";
  private final static String DEFAULT_DB = "default";

  // Our partition file paths are returned in the format of:
  // hdfs://<host>:<port>/<table>/year=2009/month=4/-47469796--667104359_25784_data.0
  // Everything after the month=4 can vary run to run so we want to filter this out
  // when comparing expected vs actual results. We also want to filter out the
  // host/port because that could vary run to run as well.
  private final static String HDFS_FILE_PATH_FILTER = "-*\\d+--\\d+_\\d+.*$";
  private final static String HDFS_HOST_PORT_FILTER = "//\\w+:\\d+/";

  private static final int DEFAULT_BEESWAX_PORT = 21000;
  private static final String DEFAULT_FE_HOST = "localhost";

  // Maps from uppercase type name to PrimitiveType
  private static Map<String, PrimitiveType> typeNameMap_ =
      new HashMap<String, PrimitiveType>();
  static {
    for(PrimitiveType type: PrimitiveType.values()) {
      typeNameMap_.put(type.toString(), type);
    }
  }

  /**
   * Return the database and the tablename in an array of the given tablename. Db will be
   * at index 0, table name will be at index 1.
   * If the tableName is not database qualified (without the db. prefix), "default" will
   * be returned as the database name
   * @param tableName
   * @return
   */
  public static String[] splitDbTablename(String tableName) {
    String db = DEFAULT_DB;
    String tblName = tableName.trim();
    String db_tblname[] = tblName.split("\\.");
    if (db_tblname.length == 2) {
      db = db_tblname[0];
      tblName = db_tblname[1];
    }
    String[] ans = { db, tblName };
    return ans;
  }

  /**
   * Do a line-by-line comparison of actual and expected output.
   * Comparison of the individual lines ignores whitespace.
   * If an expected line starts with expectedFilePrefix,
   * then the expected vs. actual comparison is successful if the actual string contains
   * the expected line (ignoring the expectedFilePrefix prefix).
   * If orderMatters is false, we consider actual to match expected if they
   * both contains the same output lines regardless of order.
   *
   * @return an error message if actual does not match expected, "" otherwise.
   */
  public static String compareOutput(
      ArrayList<String> actual, ArrayList<String> expected, boolean orderMatters) {
    if (!orderMatters) {
      Collections.sort(actual);
      Collections.sort(expected);
    }
    int mismatch = -1; // line in actual w/ mismatch
    int maxLen = Math.min(actual.size(), expected.size());
    for (int i = 0; i < maxLen; ++i) {
      String expectedStr = expected.get(i).trim();
      String actualStr = actual.get(i);
      // Look for special prefixes in containsPrefixes.
      boolean containsPrefix = false;
      for (int prefixIdx = 0; prefixIdx < expectedFilePrefix_.length; ++prefixIdx) {
        containsPrefix = expectedStr.contains(expectedFilePrefix_[prefixIdx]);
        if (containsPrefix) {
          expectedStr = expectedStr.replaceFirst(expectedFilePrefix_[prefixIdx], "");
          actualStr = actualStr.replaceFirst(expectedFilePrefix_[prefixIdx], "");
          expectedStr = applyHdfsFilePathFilter(expectedStr);
          actualStr = applyHdfsFilePathFilter(actualStr);
          break;
        }
      }

      boolean ignoreAfter = false;
      for (int j = 0; j < ignoreContentAfter_.length; ++j) {
        ignoreAfter |= expectedStr.startsWith(ignoreContentAfter_[j]);
      }

      if (expectedStr.startsWith(regexAgainstActual_)) {
        // Get regex to check against by removing prefix.
        String regex = expectedStr.replace(regexAgainstActual_, "").trim();
        if (!actualStr.matches(regex)) {
          mismatch = i;
          break;
        }
        // Accept actualStr.
        continue;
      }

      // do a whitespace-insensitive comparison
      Scanner e = new Scanner(expectedStr);
      Scanner a = new Scanner(actualStr);
      while (a.hasNext() && e.hasNext()) {
        if (containsPrefix) {
          if (!a.next().contains(e.next())) {
            mismatch = i;
            break;
          }
        } else {
          if (!a.next().equals(e.next())) {
            mismatch = i;
            break;
          }
        }
      }
      if (mismatch != -1) {
        break;
      }

      if (ignoreAfter) {
        if (e.hasNext() && !a.hasNext()) {
          mismatch = i;
          break;
        }
      } else if (a.hasNext() != e.hasNext()) {
        mismatch = i;
        break;
      }
    }
    if (mismatch == -1 && actual.size() < expected.size()) {
      // actual is a prefix of expected
      StringBuilder output =
          new StringBuilder("actual result is missing lines:\n");
      for (int i = 0; i < actual.size(); ++i) {
        output.append(actual.get(i)).append("\n");
      }
      output.append("missing:\n");
      for (int i = actual.size(); i < expected.size(); ++i) {
        output.append(expected.get(i)).append("\n");
      }
      return output.toString();
    }

    if (mismatch != -1) {
      // print actual and expected, highlighting mismatch
      StringBuilder output =
          new StringBuilder("actual result doesn't match expected result:\n");
      for (int i = 0; i <= mismatch; ++i) {
        output.append(actual.get(i)).append("\n");
      }
      // underline mismatched line with "^^^..."
      for (int i = 0; i < actual.get(mismatch).length(); ++i) {
        output.append('^');
      }
      output.append("\n");
      for (int i = mismatch + 1; i < actual.size(); ++i) {
        output.append(actual.get(i)).append("\n");
      }
      output.append("\nexpected:\n");
      for (String str : expected) {
        output.append(str).append("\n");
      }
      return output.toString();
    }

    if (actual.size() > expected.size()) {
      // print actual and expected
      StringBuilder output =
          new StringBuilder("actual result contains extra output:\n");
      for (String str : actual) {
        output.append(str).append("\n");
      }
      output.append("\nexpected:\n");
      for (String str : expected) {
        output.append(str).append("\n");
      }
      return output.toString();
    }

    return "";
  }

  /**
   * Do an element-by-element comparison of actual and expected types.
   * @return an error message if actual does not match expected, "" otherwise.
   */
  private static String compareOutputTypes(
      List<String> actualTypes, List<String> expectedTypes) {
    if (actualTypes.size() != expectedTypes.size()) {
      return "Unequal number of output types.\nFound: " + actualTypes.toString()
          + ".\nExpected: " + expectedTypes.toString() + "\n";
    }

    for (int i = 0; i < expectedTypes.size(); ++i) {
      if (!actualTypes.get(i).toUpperCase().trim().equals(
          expectedTypes.get(i).toUpperCase().trim())) {
        return "Mismatched output types.\nFound: " + actualTypes.toString() +
               ".\nExpected: " + expectedTypes.toString() + "\n";
      }
    }
    return "";
  }

  /**
   * Applied a filter on the HDFS path to strip out information that might vary
   * from run to run.
   */
  private static String applyHdfsFilePathFilter(String hdfsPath) {
    hdfsPath = hdfsPath.replaceAll(HDFS_HOST_PORT_FILTER, " ");
    return hdfsPath.replaceAll(HDFS_FILE_PATH_FILTER, "");
  }

  /**
   * Executes query, retrieving query results and error messages.
   * Compares the following actual vs. expected outputs:
   * 1. Actual and expected types of exprs in the query's select list.
   * 2. Actual and expected query results if expectedResults is non-null.
   * 3. Actual and expected errors if expectedErrors and expectedFileErrors are non-null.
   * 4. Actual and expected partitions and number of appended rows if they are non-null.
   *
   * @param executor
   *          Coordinator to run query with.
   * @param query
   *          Query to be executed.
   * @param context
   *          The context of how to execute the query. Contains details such as the
   *          number of execution nodes, if codegen is enabled, etc...
   * @param abortOnError
   *          Indicates whether the query should abort if data errors are encountered.
   *          If abortOnError is true and expectedErrors is not null, then an
   *          ImpalaException is expected to occur during query execution. The actual
   *          and expected errors will be compared in a catch clause.
   * @param maxErrors
   *          Indicates the maximum number of errors to gather.
   * @param lineNum
   *          Line number in test file on which this test query started.
   * @param expectedResults
   *          Expected query results. Used for comparison versus actual results.
   * @param testErrorLog
   *          Records error messages of failed tests to be reported at the very end of
   *          a test run.
   * @return A QueryExecTestResults object that contains information on the query
   *         execution result.
   */
  public static QueryExecTestResult runQuery(ImpaladClientExecutor executor,
      String query, TestExecContext context, int lineNum,
      QueryExecTestResult expectedExecResults, StringBuilder testErrorLog) {
    Preconditions.checkNotNull(executor);
    String queryReportString = buildQueryDetailString(query, context);
    LOG.info("running query targeting impalad " + queryReportString);

    BlockingQueue<String> resultQueue = new LinkedBlockingQueue<String>();
    ArrayList<String> errors = new ArrayList<String>();
    SortedMap<String, Integer> fileErrors = new TreeMap<String, Integer>();
    ArrayList<String> colLabels = new ArrayList<String>();
    ArrayList<String> colTypes = new ArrayList<String>();
    TInsertResult insertResult = null;
    if (expectedExecResults.getModifiedPartitions().size() > 0) {
      insertResult = new TInsertResult();
    }

    QueryExecTestResult actualExecResults = new QueryExecTestResult();
    TQueryOptions contextQueryOptions = context.getTQueryOptions();
    try {
      executor.runQuery(query, context, resultQueue, colTypes, colLabels, insertResult,
          errors);
    } catch(Exception e) {
      // Error message comes from exception
      errors.add(e.getMessage());

      // Compare errors if we are expecting some.
      if (contextQueryOptions.isAbort_on_error() &&
          expectedExecResults.getErrors().size() > 0) {
        compareErrors(queryReportString, context.getTQueryOptions().getMax_errors(),
            errors, expectedExecResults.getErrors(), testErrorLog);

        // TODO: this only append one error. We need to append all errors.
        actualExecResults.getErrors().addAll(errors);
        return actualExecResults;
      } else {
        testErrorLog.append(
            "Error executing query '" + queryReportString + "' on line " + lineNum
            + ":\n" + e.getMessage());
      }
      return actualExecResults;
    }

    // Check that there is no expected exception
    if (contextQueryOptions.isAbort_on_error() &&
        expectedExecResults.getErrors().size() > 0) {
      testErrorLog.append(
          "Expecting error but no exception is thrown: query '" + queryReportString);
      return actualExecResults;
    }

    // Compare error log against the expected error.
    if (!contextQueryOptions.isAbort_on_error() &&
        expectedExecResults.getErrors().size() > 0) {
      compareErrors(queryReportString, context.getTQueryOptions().getMax_errors(),
          errors, expectedExecResults.getErrors(), testErrorLog);
    }

    // Check insert results for insert statements.
    // We check the num rows and the partitions independently,
    // because not all table types create new partition files (e.g., HBase tables).
    if (expectedExecResults.getModifiedPartitions().size() > 0) {
      ArrayList<String> actualResultsArray = Lists.newArrayList();
      for (Map.Entry<String, Long> entry: insertResult.getRows_appended().entrySet()) {
        actualResultsArray.add("/" + entry.getKey() + ": " + entry.getValue());
      }

      actualExecResults.getModifiedPartitions().addAll(actualResultsArray);

      String result = TestUtils.compareOutput(actualResultsArray,
          expectedExecResults.getModifiedPartitions(), false);
      if (!result.isEmpty()) {
        testErrorLog.append("query:\n" + queryReportString + "\n" + result);
      }
    }

    // We only expect partitions and num rows for insert statements,
    // and we compared them above.
    if (expectedExecResults.getModifiedPartitions().size() > 0) {
      return actualExecResults;
    }

    // Verify query result
    ArrayList<String> actualResults = Lists.newArrayList();

    while (!resultQueue.isEmpty()) {
      String resultRow = null;
      try {
        resultRow = resultQueue.take();
      } catch (InterruptedException e) {
        e.printStackTrace();
        testErrorLog.append("unexpected interrupt");
        return null;
      }

      parseResultRow(resultRow, actualResults, colTypes);
    }

    actualExecResults.getColTypes().addAll(colTypes);
    actualExecResults.getColLabels().addAll(colLabels);
    actualExecResults.getResultSet().addAll(actualResults);

    verifyTypesLabelsAndResults(queryReportString, testErrorLog,
                                actualExecResults, expectedExecResults);
    return actualExecResults;
  }

  private static void parseResultRow(String rawResultRow, List<String> results,
      List<String> colTypes) {
    // Concatenate columns separated by ","
    String[] resultColumns = rawResultRow.split("\t");
    Assert.assertEquals(colTypes.size(), resultColumns.length);
    StringBuilder line = new StringBuilder();
    for (int i = 0; i < colTypes.size(); ++i) {
      if (i > 0) {
        line.append(',');
      }

      PrimitiveType columnType = typeNameMap_.get(colTypes.get(i).toUpperCase().trim());
      line.append(parseColumnValue(resultColumns[i], columnType));
    }

    results.add(line.toString());
  }

  /**
   * Parses column value and returns the result in the format used by the results
   * file.
   */
  private static String parseColumnValue(String columnValue, PrimitiveType columnType) {
    switch(columnType) {
      case STRING:
        return String.format("'%s'", columnValue);
      default:
        return columnValue;
    }
  }

  private static String buildQueryDetailString(String queryString,
                                               TestExecContext context) {
    return String.format("%s (%s)", queryString, context.toString());
  }

  /**
   * Performs common verification between different types of Executors.
   */
  private static void verifyTypesLabelsAndResults(String queryReportString,
      StringBuilder testErrorLog, QueryExecTestResult actualResults,
      QueryExecTestResult expectedResults) {

    String result = compareColumnLabels(actualResults.getColLabels(),
                                        expectedResults.getColLabels());
    if (!result.isEmpty()) {
      testErrorLog.append("query:\n" + queryReportString + "\n" + result);
      return;
    }

    result = compareExpectedTypes(actualResults.getColTypes(),
                                  expectedResults.getColTypes());
    if (!result.isEmpty()) {
      testErrorLog.append("query:\n" + queryReportString + "\n" + result);
      return;
    }

    result = compareExpectedResults(actualResults.getResultSet(),
                                    expectedResults.getResultSet(), false);
    if (!result.isEmpty()) {
      testErrorLog.append("query:\n" + queryReportString + "\n" + result);
    }
  }

  /**
   * Compares the actual and expected column labels, returning an error string
   * if they do not match.
   */
  private static String compareColumnLabels(ArrayList<String> actualColLabels,
                                            ArrayList<String> expectedColLabels) {
    return (expectedColLabels.size() > 0) ?
        TestUtils.compareOutput(actualColLabels, expectedColLabels, true) : "";
  }

  private static String compareExpectedTypes(ArrayList<String> actualTypes,
      ArrayList<String> expectedTypes) {
    // Check types filled in by RunQuery()
    if (expectedTypes.size() > 0) {
      return TestUtils.compareOutputTypes(actualTypes,
          Lists.newArrayList(expectedTypes.get(0).split(",")));
    }
    return "";
  }

  /**
   * Compares actual and expected results of a query execution.
   *
   * @param actualResults
   *        The actual results of executing the query
   * @param expectedResults
   *        The expected results of executing the query
   * @param containsOrderBy
   *        Whether the query contains an ORDER BY clause. If it does not then
   *        the results are sorted before comparison.
   * @return
   *        Returns an empty string if the results are the same, otherwise a string
   *        describing how the results differ.
   */
  private static String compareExpectedResults(ArrayList<String> actualResults,
      ArrayList<String> expectedResults, boolean containsOrderBy) {
    return (expectedResults != null) ?
        TestUtils.compareOutput(actualResults, expectedResults, containsOrderBy) : "";
  }

  /**
   * Verify actual error against expected error.
   * @param query
   * @param maxError the maximum number of error expected from actualErrors
   * @param actualErrors the actual errors reported
   * @param expectedErrors the possible set of errors from actualErrors
   * @param testErrorLog an empty string if the results are the same, otherwise a string
   *        describing how the results differ.
   */
  private static void compareErrors(String query, int maxError,
      ArrayList<String> actualErrors, ArrayList<String> expectedErrors,
      StringBuilder testErrorLog) {
    StringBuilder errorLog = new StringBuilder();
    String errorPrefix = "query:\n" + query + "\n";
    int realMaxError = Math.min(maxError, expectedErrors.size());
    // Number of actual errors should not exceed realMaxError
    // TODO: it should actually MATCH the expected one.
    if (actualErrors.size() > realMaxError) {
      testErrorLog.append(errorPrefix + "got " + actualErrors.size() +
          " but expect at most" + realMaxError + " errors");
      return;
    }

    // Actual errors is a subset of expected errors
    // Each item in expectedErrors has only one line, but an actualError has multiple
    // lines where each line should map to an item in expectedErrors.
    // Break actualError into a lines and check that every line exists in
    // expectedErrors.
    for (String actualError: actualErrors) {
      actualError = applyHdfsFilePathFilter(actualError);
      ArrayList<String> actualErrorList = Lists.newArrayList();
      actualErrorList.addAll(Arrays.asList(actualError.split("\n")));
      for (String actualErrorListItem: actualErrorList) {
        // Ignore empty line
        if (actualErrorListItem.isEmpty()) {
          continue;
        }
        // TODO: Strip out the filename because it is non-deterministic but we should
        // validate that we are reporting the correct filename.
        if (actualErrorListItem.startsWith("file:")) {
          actualErrorListItem =
              actualErrorListItem.substring(0, actualErrorListItem.lastIndexOf("/"));
        }
        // Strip out Backend number
        if (actualErrorListItem.startsWith("Backend ")) {
          actualErrorListItem = actualErrorListItem.replaceAll("Backend \\d+:", "");
        }
        if (!expectedErrors.contains(actualErrorListItem)) {
          errorLog.append("Unexpected Error: ").append(actualErrorListItem).append("\n");
        }
      }
    }

    if (errorLog.length() > 0) {
      testErrorLog.append(errorPrefix);
      testErrorLog.append(errorLog);
    }
    return;
  }

  /**
   * Return an ImpaladClientExecutor that has been connected to an external ImpalaServer.
   */
  public static ImpaladClientExecutor createImpaladClientExecutor() {
    String hostName = System.getProperty("impalad", DEFAULT_FE_HOST);
    int beeswaxPort = DEFAULT_BEESWAX_PORT;
    ImpaladClientExecutor client = null;
    try {
      beeswaxPort = Integer.parseInt(
          System.getProperty("BEESWAX_PORT", Integer.toString(DEFAULT_BEESWAX_PORT)));
    } catch (NumberFormatException nfe) {
      fail("Invalid port format.");
    }

    client = new ImpaladClientExecutor(hostName, beeswaxPort);
    try {
      client.init();
    } catch (TTransportException e) {
      e.printStackTrace();
      fail("Error opening transport: " + e.getMessage());
    }

    return client;
  }

  /**
   * Create a TQueryContext for executing FE tests.
   */
  public static TQueryContext createQueryContext() {
    return createQueryContext(Catalog.DEFAULT_DB, System.getProperty("user.name"));
  }

  /**
   * Create a TQueryContext for executing FE tests using the given default DB and user.
   */
  public static TQueryContext createQueryContext(String defaultDb, String user) {
    TQueryContext queryCtxt = new TQueryContext();
    queryCtxt.setRequest(new TClientRequest("FeTests", new TQueryOptions()));
    queryCtxt.setSession(new TSessionState(new TUniqueId(), TSessionType.BEESWAX,
        defaultDb, user, new TNetworkAddress("localhost", 0)));
    SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSSSSSSS");
    queryCtxt.setNow_string(formatter.format(Calendar.getInstance().getTime()));
    queryCtxt.setPid(1000);
    return queryCtxt;
  }
}
