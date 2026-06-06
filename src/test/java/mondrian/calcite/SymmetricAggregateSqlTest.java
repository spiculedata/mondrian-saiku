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

            // Bridge / many-to-many fixture (#107): accounts owned by
            // customers via a bridge with allocation weights.
            //   account 1: balance 1000, owned 50/50 by Alice & Bob
            //   account 2: balance  500, owned 100% by Bob
            st.execute(
                "CREATE TABLE accounts (account_id INTEGER, balance INTEGER)");
            st.execute(
                "CREATE TABLE account_owner (account_id INTEGER,"
                + " customer VARCHAR(32), weight DECIMAL(5,4))");
            st.execute("INSERT INTO accounts VALUES (1, 1000)");
            st.execute("INSERT INTO accounts VALUES (2, 500)");
            st.execute(
                "INSERT INTO account_owner VALUES (1, 'Alice', 0.5)");
            st.execute(
                "INSERT INTO account_owner VALUES (1, 'Bob', 0.5)");
            st.execute(
                "INSERT INTO account_owner VALUES (2, 'Bob', 1.0)");
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

    private long scalarFor(String sql) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql))
        {
            rs.next();
            return Math.round(rs.getDouble(1));
        }
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

    // ---- bridge (many-to-many) aggregation semantics (#107) ----------

    private long balanceFor(String customer, boolean weighted)
        throws Exception
    {
        String inner = weighted
            // weighted: split each balance by its ownership weight.
            ? "SELECT SUM(a.balance * o.weight) FROM accounts a"
              + " JOIN account_owner o ON a.account_id = o.account_id"
              + " WHERE o.customer = '" + customer + "'"
            // fullCount: each customer gets the whole balance of every
            // account they own — deduped per account PK within the cell
            // (#103 pre-aggregation form).
            : "SELECT SUM(balance) FROM ("
              + "  SELECT DISTINCT a.account_id, a.balance"
              + "  FROM accounts a JOIN account_owner o"
              + "    ON a.account_id = o.account_id"
              + "  WHERE o.customer = '" + customer + "') t";
        return scalarFor(inner);
    }

    /** fullCount: shared account counted in full under each owner. */
    @Test
    public void bridgeFullCountSemantics() throws Exception {
        assertEquals(1000L, balanceFor("Alice", false), "Alice = 1000");
        assertEquals(1500L, balanceFor("Bob", false), "Bob = 1000 + 500");
    }

    /** weighted: each balance split across owners by weight (reconciles). */
    @Test
    public void bridgeWeightedSemantics() throws Exception {
        assertEquals(500L, balanceFor("Alice", true), "Alice = 1000*0.5");
        assertEquals(
            1000L, balanceFor("Bob", true), "Bob = 1000*0.5 + 500*1.0");
    }

    // ---- edge cases from the #107 audit (SQL-level semantics) ----------

    /**
     * Weighted weights that do NOT sum to 1.0 are applied literally — there is
     * no implicit normalization. Account 1 is split 0.3 / 0.3 (sum 0.6), so
     * its All-level weighted total is 1000*0.6 = 600, NOT the full 1000.
     */
    @Test
    public void weightedWeightsNotSummingToOne() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE acct2 (account_id INTEGER,"
                + " balance INTEGER)");
            st.execute("CREATE TABLE own2 (account_id INTEGER,"
                + " customer VARCHAR(32), weight DECIMAL(5,4))");
            st.execute("INSERT INTO acct2 VALUES (1, 1000)");
            st.execute("INSERT INTO own2 VALUES (1, 'Alice', 0.3)");
            st.execute("INSERT INTO own2 VALUES (1, 'Bob', 0.3)");
        }
        long allWeighted = Math.round(scalarDouble(
            "SELECT SUM(a.balance * o.weight) FROM acct2 a"
            + " JOIN own2 o ON a.account_id = o.account_id"));
        assertEquals(600L, allWeighted,
            "0.3 + 0.3 applied literally (no normalization to 1.0)");
    }

    /**
     * Full-count with THREE owners on one account still de-duplicates to the
     * single account balance at the All level (counted once, not thrice).
     */
    @Test
    public void fullCountThreeOwnersDedup() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE acct3 (account_id INTEGER,"
                + " balance INTEGER)");
            st.execute("CREATE TABLE own3 (account_id INTEGER,"
                + " customer VARCHAR(32))");
            st.execute("INSERT INTO acct3 VALUES (1, 1000)");
            st.execute("INSERT INTO own3 VALUES (1, 'Alice')");
            st.execute("INSERT INTO own3 VALUES (1, 'Bob')");
            st.execute("INSERT INTO own3 VALUES (1, 'Carol')");
        }
        long all = scalar(
            "SELECT SUM(balance) FROM ("
            + "  SELECT DISTINCT a.account_id, a.balance"
            + "  FROM acct3 a JOIN own3 o"
            + "    ON a.account_id = o.account_id) t");
        assertEquals(1000L, all,
            "3 owners → deduped to one account balance, not 3000");
    }

    /**
     * Defined behaviour for a NULL weight and a NULL bridge key, rather than a
     * silent miscount. A NULL weight makes {@code balance * weight} NULL (that
     * row contributes nothing to the weighted SUM); a NULL bridge key never
     * matches the inner join, so its account drops out entirely.
     */
    @Test
    public void nullWeightAndNullBridgeKey() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE acctN (account_id INTEGER,"
                + " balance INTEGER)");
            st.execute("CREATE TABLE ownN (account_id INTEGER,"
                + " customer VARCHAR(32), weight DECIMAL(5,4))");
            st.execute("INSERT INTO acctN VALUES (1, 1000)");
            st.execute("INSERT INTO acctN VALUES (2, 500)");
            // account 1: NULL weight; account 2: NULL bridge key.
            st.execute("INSERT INTO ownN VALUES (1, 'Alice', NULL)");
            st.execute("INSERT INTO ownN VALUES (NULL, 'Bob', 1.0)");
        }
        // Weighted SUM: account 1 contributes balance*NULL = NULL (ignored);
        // account 2 never joins (NULL key) → total weighted is NULL/empty.
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT SUM(a.balance * o.weight) FROM acctN a"
                 + " JOIN ownN o ON a.account_id = o.account_id"))
        {
            rs.next();
            rs.getDouble(1);
            assertEquals(true, rs.wasNull(),
                "NULL weight + NULL key leave no contributing rows → NULL");
        }
        // Full-count: only account 1 has a (non-null-key) owner row.
        long fc = scalar(
            "SELECT SUM(balance) FROM ("
            + "  SELECT DISTINCT a.account_id, a.balance"
            + "  FROM acctN a JOIN ownN o"
            + "    ON a.account_id = o.account_id) t");
        assertEquals(1000L, fc,
            "account 2 (NULL bridge key) drops from the inner join");
    }

    /** An empty bridge table yields an all-NULL (empty) result, not zero rows
     *  miscounted as a value. */
    @Test
    public void emptyBridgeTableYieldsAllNull() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE acctE (account_id INTEGER,"
                + " balance INTEGER)");
            st.execute("CREATE TABLE ownE (account_id INTEGER,"
                + " customer VARCHAR(32))");
            st.execute("INSERT INTO acctE VALUES (1, 1000)");
            // ownE intentionally empty.
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT SUM(balance) FROM ("
                 + "  SELECT DISTINCT a.account_id, a.balance"
                 + "  FROM acctE a JOIN ownE o"
                 + "    ON a.account_id = o.account_id) t"))
        {
            rs.next();
            rs.getDouble(1);
            assertEquals(true, rs.wasNull(),
                "no bridge rows → empty join → NULL total (not 0, not 1000)");
        }
    }

    /**
     * A fan-out SUM and a NON-fan-out additive measure in the SAME query: only
     * the fan-out measure must be de-duplicated (DISTINCT on the fact grain),
     * while the additive line-item count aggregates normally over the join.
     * Revenue = 150 (deduped), item count = 4 (all line items).
     */
    @Test
    public void symmetricWithNormalAdditiveMeasureSameQuery() throws Exception {
        long revenue = scalar(
            "SELECT SUM(total) FROM ("
            + "  SELECT DISTINCT o.order_id, o.total"
            + "  FROM orders o JOIN line_items li"
            + "    ON o.order_id = li.order_id) t");
        long items = scalar(
            "SELECT COUNT(*) FROM orders o JOIN line_items li"
            + " ON o.order_id = li.order_id");
        assertEquals(150L, revenue, "fan-out revenue deduped to 150");
        assertEquals(4L, items,
            "additive line-item count is NOT deduped (all 4 over the join)");
    }

    /**
     * An empty grain-column list (no grouping columns) falls back gracefully:
     * DISTINCT on the fact PK alone still collapses the fan-out to the true
     * total (150) rather than the naive double-counted SUM (350).
     */
    @Test
    public void symmetricGrainColumnNull() throws Exception {
        long total = scalar(
            "SELECT SUM(total) FROM ("
            + "  SELECT DISTINCT o.order_id, o.total"
            + "  FROM orders o JOIN line_items li"
            + "    ON o.order_id = li.order_id) t");
        assertEquals(150L, total,
            "no group columns → DISTINCT on PK still dedupes (not 350)");
    }

    private double scalarDouble(String sql) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql))
        {
            rs.next();
            return rs.getDouble(1);
        }
    }
}
