/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2002-2005 Julian Hyde
// Copyright (C) 2005-2012 Pentaho and others
// All Rights Reserved.
*/
package mondrian.test;

import mondrian.olap.Util;
import mondrian.test.clearview.*;

import junit.framework.*;

import java.lang.reflect.Constructor;
import java.util.*;

/**
 * A copy of {@link ConcurrentMdxTest} with modifications to take
 * as input ref.xml files. This does not fully use {@link DiffRepository}
 * and does not generate log files.
 * This Class is not added to the Main test suite.
 * Purpose of this test is to simulate Concurrent access to Aggregation and data
 * load. Simulation will be more effective if we run this single test again and
 * again with a fresh connection.
 *
 * @author Khanh Vu
 */
public class CVConcurrentMdxTest extends FoodMartTestCase {
    public CVConcurrentMdxTest() {
        super();
    }

    public void testConcurrentQueriesInRandomOrder() throws Exception {
        propSaver.set(propSaver.props.DisableCaching, false);
        propSaver.set(propSaver.props.UseAggregates, false);
        propSaver.set(propSaver.props.ReadAggregates, false);
        // ClearViewBase.runTest sets this before every query, so every
        // reference result these tests borrow was recorded with it on. Run
        // them without it and the queries take a different evaluation path:
        // rows come back in another order, and an unqualified dimension
        // reference resolves differently. Neither has anything to do with
        // running them concurrently, which is what this test is for.
        propSaver.set(propSaver.props.ExpandNonNative, true);

        // test partially filled aggregation cache
        // add test classes
        List<Class> testList = new ArrayList<Class>();
        List<TestSuite> suiteList = new ArrayList<TestSuite>();

        testList.add(PartialCacheTest.class);
        suiteList.add(PartialCacheTest.suite());
        testList.add(MultiLevelTest.class);
        suiteList.add(MultiLevelTest.suite());
        testList.add(QueryAllTest.class);
        suiteList.add(QueryAllTest.suite());
        testList.add(MultiDimTest.class);
        suiteList.add(MultiDimTest.suite());

        // sanity check
        assertTrue(sanityCheck(suiteList));

        // generate list of queries and results
        QueryAndResult[] queryList = generateQueryArray(testList);

        final List<Throwable> throwables =
            ConcurrentValidatingQueryRunner.runTest(
                3, 100, true, true, true, queryList);
        assertEquals(0, throwables.size());
    }

    public void testConcurrentQueriesInRandomOrderOnVirtualCube()
        throws Exception
    {
        propSaver.set(propSaver.props.DisableCaching, false);
        propSaver.set(propSaver.props.UseAggregates, false);
        propSaver.set(propSaver.props.ReadAggregates, false);
        // ClearViewBase.runTest sets this before every query, so every
        // reference result these tests borrow was recorded with it on. Run
        // them without it and the queries take a different evaluation path:
        // rows come back in another order, and an unqualified dimension
        // reference resolves differently. Neither has anything to do with
        // running them concurrently, which is what this test is for.
        propSaver.set(propSaver.props.ExpandNonNative, true);

        // test partially filled aggregation cache
        // add test classes
        List<Class> testList = new ArrayList<Class>();
        List<TestSuite> suiteList = new ArrayList<TestSuite>();

        testList.add(PartialCacheVCTest.class);
        suiteList.add(PartialCacheVCTest.suite());
        testList.add(MultiLevelTest.class);
        suiteList.add(MultiLevelTest.suite());
        testList.add(QueryAllVCTest.class);
        suiteList.add(QueryAllVCTest.suite());
        testList.add(MultiDimVCTest.class);
        suiteList.add(MultiDimVCTest.suite());

        // sanity check
        assertTrue(sanityCheck(suiteList));

        // generate list of queries and results
        QueryAndResult[] queryList = generateQueryArray(testList);

        final List<Throwable> throwables =
            ConcurrentValidatingQueryRunner.runTest(
                3, 100, true, true, true, queryList);
        assertEquals(0, throwables.size());
    }

    public void testConcurrentCVQueriesInRandomOrder() throws Exception {
        propSaver.set(propSaver.props.DisableCaching, false);
        propSaver.set(propSaver.props.UseAggregates, false);
        propSaver.set(propSaver.props.ReadAggregates, false);
        // ClearViewBase.runTest sets this before every query, so every
        // reference result these tests borrow was recorded with it on. Run
        // them without it and the queries take a different evaluation path:
        // rows come back in another order, and an unqualified dimension
        // reference resolves differently. Neither has anything to do with
        // running them concurrently, which is what this test is for.
        propSaver.set(propSaver.props.ExpandNonNative, true);

        // test partially filled aggregation cache
        // add test classes
        List<Class> testList = new ArrayList<Class>();

        testList.add(CVBasicTest.class);
        testList.add(GrandTotalTest.class);
        testList.add(MetricFilterTest.class);
        testList.add(MiscTest.class);
        testList.add(PredicateFilterTest.class);
        testList.add(SubTotalTest.class);
        testList.add(SummaryMetricPercentTest.class);
        testList.add(SummaryTest.class);
        testList.add(TopBottomTest.class);

        // generate list of queries and results
        QueryAndResult[] queryList = generateQueryArray(testList);

        assertEquals(
            Collections.<Throwable>emptyList(),
            ConcurrentValidatingQueryRunner.runTest(
                3, 100, true, true, true, queryList));
    }

    protected void tearDown() throws Exception {
        super.tearDown();
    }

    protected void setUp() throws Exception {
        super.setUp();
    }

    /**
     * Runs one pass of all tests single-threaded using
     * {@link mondrian.test.clearview.ClearViewBase} mechanism
     * @param suiteList list of tests to be checked
     * @return true if all tests pass
     */
    private boolean sanityCheck(List<TestSuite> suiteList) {
        TestSuite suite = new TestSuite();

        for (TestSuite suite1 : suiteList) {
            suite.addTest(suite1);
        }

        TestResult tres = new TestResult();
        suite.run(tres);

        return tres.wasSuccessful();
    }

    /**
     * Generates an array of QueryAndResult objects from the list of
     * test classes
     * @param testList list of test classes
     * @return array of QueryAndResult
     * @throws Exception on error
     */
    private QueryAndResult[] generateQueryArray(List<Class> testList)
        throws Exception
    {
        List<QueryAndResult> queryList = new ArrayList<QueryAndResult>();
        int skipped = 0;
        for (Class testClass : testList) {
            Class[] types = {String.class};
            Constructor cons = testClass.getConstructor(types);
            Object[] args = {""};
            Test newCon = (Test) cons.newInstance(args);
            DiffRepository diffRepos =
                ((ClearViewBase) newCon).getDiffRepos();

            List<String> testCaseNames = diffRepos.getTestCaseNames();
            for (String testCaseName : testCaseNames) {
                String query = diffRepos.get(testCaseName, "mdx");

                // Only run queries whose reference result was recorded
                // against the plain schema. ClearViewBase.runTest builds a
                // substituting-cube context whenever the case declares any
                // of these, and the recorded result is a result FOR THAT
                // SCHEMA -- member unique names and row order included. Run
                // against the plain connection here, such a case cannot
                // match, and the failure looks like a concurrency bug.
                // (calculatedMembers was already excluded; the other four
                // modify the cube in exactly the same way.)
                if (diffRepos.get(testCaseName, "calculatedMembers") == null
                    && diffRepos.get(testCaseName, "modifiedCubeName") == null
                    && diffRepos.get(testCaseName, "customDimensions") == null
                    && diffRepos.get(testCaseName, "measures") == null
                    && diffRepos.get(testCaseName, "namedSets") == null)
                {
                    // Expect what this query returns HERE, run once on its
                    // own -- not the result recorded in the reference file.
                    // Those results were recorded by ClearViewBase, which
                    // runs each query through its own context and property
                    // settings; borrowing them means a difference in how the
                    // query is set up reads as a concurrency failure. What
                    // this test is for is that running these queries
                    // concurrently gives the same answers as running them one
                    // at a time, and that is now what it compares.
                    try {
                        queryList.add(
                            new QueryAndResult(
                                query,
                                TestContext.toString(
                                    getTestContext().executeQuery(query))));
                    } catch (RuntimeException e) {
                        // Some ClearView queries only run against the context
                        // their own suite builds for them. Skip those rather
                        // than report them as concurrency failures, and say
                        // how many were skipped so the coverage this test
                        // gives is not overstated.
                        ++skipped;
                    }
                }
            }
        }
        System.out.println(
            "CVConcurrentMdxTest: running " + queryList.size()
            + " queries concurrently; skipped " + skipped
            + " that do not run against the plain schema.");
        return queryList.toArray(new QueryAndResult[queryList.size()]);
    }
}

// End CVConcurrentMdxTest.java
