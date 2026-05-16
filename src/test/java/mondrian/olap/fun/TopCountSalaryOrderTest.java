/*
 *   Copyright 2026 Spicule Ltd
 *   Eclipse Public License v1.0.
 */
package mondrian.olap.fun;

import mondrian.olap.MondrianProperties;
import mondrian.test.FoodMartTestCase;

/**
 * saiku#809 — TopCount on the HR/Employee/Salary level does not return
 * rows in strict descending order of the sort criterion. Probe test:
 * runs the exact MDX from the bug report under default and
 * native-disabled configurations, prints what comes back, asserts only
 * the well-known top row so we get a diagnostic dump in CI output.
 */
public class TopCountSalaryOrderTest extends FoodMartTestCase {

    private static final String MDX =
            "SELECT NON EMPTY {[Measures].[Number of Employees]} ON COLUMNS,\n"
            + "NON EMPTY TopCount([Employee].[Salary].[Salary].Members, 5,"
            + " [Measures].[Number of Employees]) ON ROWS\n"
            + "FROM [HR]";

    /** Default config — native TopCount enabled. */
    public void testTopCountSalaryDefault() {
        // saiku#809 SQL trace
        org.apache.log4j.Logger sqlLog = mondrian.rolap.RolapUtil.SQL_LOGGER;
        org.apache.log4j.Level prevLevel = sqlLog.getLevel();
        org.apache.log4j.Appender appender = new org.apache.log4j.ConsoleAppender(
                new org.apache.log4j.PatternLayout("[mondrian.sql] %m%n"));
        sqlLog.addAppender(appender);
        sqlLog.setLevel(org.apache.log4j.Level.DEBUG);
        try {
            String actual = renderQuery(MDX);
            System.out.println("=== saiku#809 DEFAULT ===\n" + actual);
            assertTrue("top row should be 283 count: " + actual, actual.contains("Row #0: 283"));
        } finally {
            sqlLog.removeAppender(appender);
            sqlLog.setLevel(prevLevel);
        }
    }

    /** {@code EnableNativeTopCount=false} — Java path. */
    public void testTopCountSalaryNativeOff() {
        MondrianProperties props = MondrianProperties.instance();
        boolean wasEnabled = props.EnableNativeTopCount.get();
        props.EnableNativeTopCount.set(false);
        try {
            String actual = renderQuery(MDX);
            System.out.println("=== saiku#809 NATIVE-OFF ===\n" + actual);
            assertTrue("top row should be 283 count: " + actual, actual.contains("Row #0: 283"));
        } finally {
            props.EnableNativeTopCount.set(wasEnabled);
        }
    }

    /** Without NON EMPTY — does the wrapper re-order? */
    public void testTopCountWithoutNonEmpty() {
        String mdx =
                "SELECT {[Measures].[Number of Employees]} ON COLUMNS,\n"
                + "TopCount([Employee].[Salary].[Salary].Members, 5,"
                + " [Measures].[Number of Employees]) ON ROWS\n"
                + "FROM [HR]";
        String actual = renderQuery(mdx);
        System.out.println("=== saiku#809 NO-NON-EMPTY ===\n" + actual);
        assertTrue("top row should be 283 count: " + actual, actual.contains("Row #0: 283"));
    }

    /** Native off + no NON EMPTY → pure-Java partiallySortList only. */
    public void testTopCountNativeOffNoNonEmpty() {
        MondrianProperties props = MondrianProperties.instance();
        boolean wasEnabled = props.EnableNativeTopCount.get();
        props.EnableNativeTopCount.set(false);
        try {
            String mdx =
                    "SELECT {[Measures].[Number of Employees]} ON COLUMNS,\n"
                    + "TopCount([Employee].[Salary].[Salary].Members, 5,"
                    + " [Measures].[Number of Employees]) ON ROWS\n"
                    + "FROM [HR]";
            String actual = renderQuery(mdx);
            System.out.println("=== saiku#809 NATIVE-OFF NO-NON-EMPTY ===\n" + actual);
            assertTrue("top row should be 283 count: " + actual, actual.contains("Row #0: 283"));
        } finally {
            props.EnableNativeTopCount.set(wasEnabled);
        }
    }

    /** Top 10 — does the bug show in rows 3/4 still, or do rows beyond 5
     *  come back in the right order? Tells us if H2's setMaxRows is
     *  truncating before the sort completes. */
    public void testTopCount10Salary() {
        String mdx =
                "SELECT NON EMPTY {[Measures].[Number of Employees]} ON COLUMNS,\n"
                + "NON EMPTY TopCount([Employee].[Salary].[Salary].Members, 10,"
                + " [Measures].[Number of Employees]) ON ROWS\n"
                + "FROM [HR]";
        String actual = renderQuery(mdx);
        System.out.println("=== saiku#809 TOP 10 ===\n" + actual);
        assertTrue("top row should be 283 count: " + actual, actual.contains("Row #0: 283"));
    }

    /**
     * Cross-check: Order(...,BDESC) on the same set must return strict
     * descending — if this fails too, the bug is upstream of TopCount.
     */
    public void testOrderBdescIsStrictDescending() {
        String orderMdx =
                "SELECT NON EMPTY {[Measures].[Number of Employees]} ON COLUMNS,\n"
                + "NON EMPTY Order([Employee].[Salary].[Salary].Members,"
                + " [Measures].[Number of Employees], BDESC) ON ROWS\n"
                + "FROM [HR]";
        String actual = renderQuery(orderMdx);
        System.out.println("=== saiku#809 ORDER BDESC ===\n" + actual);
        assertTrue("top row should be 283 count: " + actual, actual.contains("Row #0: 283"));
    }

    private String renderQuery(String mdx) {
        return mondrian.test.TestContext.toString(executeQuery(mdx));
    }
}
