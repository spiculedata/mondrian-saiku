/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.calcite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #103, step 1: prove the fan-out-safe ("symmetric") aggregate SQL is
 * correct on the target database, before wiring any of it into the model.
 *
 * <p>The fan-out: an order header carries a total; joining it to its line
 * items (one order → many items) duplicates the header row once per item, so
 * a naive {@code SUM(total)} over the join double-counts. Worked example:
 * <pre>
 *   order 1: total 100, with 3 line items
 *   order 2: total  50, with 1 line item
 *   true total revenue = 150
 *   naive SUM(total) over the join = 100*3 + 50 = 350   (WRONG)
 * </pre>
 *
 * <p>The fix used here is the <em>pre-aggregation</em> form: collapse the
 * fan-out with {@code SELECT DISTINCT (pk, value, group-cols)} in a subquery,
 * then aggregate. Obviously correct, standard SQL, dialect-portable (no hash
 * trick, no overflow) — Calcite builds the subquery cleanly. This test pins
 * the SQL math against HSQLDB so the later RelBuilder emission has a
 * known-correct target.
 */
public class SymmetricAggregateSqlTest {

    private Connection conn;

    @BeforeEach
    public void setUp() throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        conn = DriverManager.getConnection(
            "jdbc:hsqldb:mem:symagg" + System.identityHashCode(this),
            "sa", "");
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE orders (order_id INTEGER, total INTEGER)");
            st.execute(
                "CREATE TABLE line_items (order_id INTEGER,"
                + " product VARCHAR(32), category VARCHAR(32))");
            st.execute("INSERT INTO orders VALUES (1, 100)");
            st.execute("INSERT INTO orders VALUES (2, 50)");
            // order 1 fans out to 3 line items, order 2 to 1.
            st.execute(
                "INSERT INTO line_items VALUES (1, 'shirt', 'apparel')");
            st.execute(
                "INSERT INTO line_items VALUES (1, 'shoes', 'apparel')");
            st.execute(
                "INSERT INTO line_items VALUES (1, 'hat', 'apparel')");
            st.execute(
                "INSERT INTO line_items VALUES (2, 'socks', 'apparel')");
        }
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (conn == null) {
            return;
        }
        try (Statement st = conn.createStatement()) {
            st.execute("SHUTDOWN");
        }
        conn.close();
    }

    private long scalar(String sql) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql))
        {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Establish the bug: the naive join sum double-counts (350, not 150). */
    @Test
    public void naiveSumOverFanoutDoubleCounts() throws Exception {
        long naive = scalar(
            "SELECT SUM(o.total) FROM orders o"
            + " JOIN line_items li ON o.order_id = li.order_id");
        assertEquals(
            350L, naive,
            "naive SUM over a one-to-many join double-counts the header");
    }

    /** The fix: pre-aggregate (DISTINCT pk,value) collapses the fan-out. */
    @Test
    public void symmetricSumOverFanoutIsCorrect() throws Exception {
        long symmetric = scalar(
            "SELECT SUM(total) FROM ("
            + "  SELECT DISTINCT o.order_id, o.total"
            + "  FROM orders o JOIN line_items li"
            + "    ON o.order_id = li.order_id) t");
        assertEquals(
            150L, symmetric,
            "fan-out-safe SUM (pre-aggregated by order PK) = true revenue");
    }

    /**
     * Sliced by a line-item attribute (category): the header still dedupes
     * within each cell. Here every line item is 'apparel', so revenue by
     * category is the full 150 — counted once per order, not once per item.
     */
    @Test
    public void symmetricSumSlicedByLineItemAttribute() throws Exception {
        long apparel = scalar(
            "SELECT SUM(total) FROM ("
            + "  SELECT DISTINCT o.order_id, o.total, li.category"
            + "  FROM orders o JOIN line_items li"
            + "    ON o.order_id = li.order_id) t"
            + " WHERE category = 'apparel'");
        assertEquals(
            150L, apparel,
            "header deduped within the category cell (not 350)");
    }
}
