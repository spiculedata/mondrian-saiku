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

import mondrian.test.calcite.HarnessBackend;
import mondrian.test.calcite.PostgresFoodMartDataSource;

import org.apache.arrow.adapter.jdbc.ArrowVectorIterator;
import org.apache.arrow.adapter.jdbc.JdbcToArrow;
import org.apache.arrow.adapter.jdbc.JdbcToArrowConfig;
import org.apache.arrow.adapter.jdbc.JdbcToArrowConfigBuilder;
import org.apache.arrow.adbc.core.AdbcConnection;
import org.apache.arrow.adbc.core.AdbcDatabase;
import org.apache.arrow.adbc.core.AdbcDriver;
import org.apache.arrow.adbc.core.AdbcStatement;
import org.apache.arrow.adbc.driver.jni.JniDriver;
import org.apache.arrow.adbc.driver.jni.JniDriverFactory;
import org.apache.arrow.adbc.drivermanager.AdbcDriverManager;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.NullCheckingForGet;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * #36 Phase 0 — Postgres validation of the Arrow tuning recipe.
 *
 * <p>Runs the same JDBC-vs-Arrow tuning matrix as
 * {@link DuckDbArrowFactReadSpikeTest} but against a Postgres
 * {@code sales_fact_1997} table — the bigger FoodMart fixture
 * documented in {@code docs/reports/postgres-foodmart-bootstrap.md}.
 * Confirms whether the recipe (bypass {@code Float8Vector} wrapper +
 * single large batch + {@code NULL_CHECKING_ENABLED=false}) scales
 * from in-process DuckDB to a remote (or remote-shaped) backend.
 *
 * <h3>Honest scope limitation</h3>
 *
 * <p>For Postgres, the Arrow path uses {@code arrow-jdbc}
 * ({@link JdbcToArrow}) which converts a row-shaped JDBC
 * {@link ResultSet} to Arrow {@link VectorSchemaRoot} batches
 * on the Java side. The actual wire transfer is still JDBC's
 * row-shaped format underneath — only the post-fetch processing is
 * columnar.
 *
 * <p>The TRUE wire-level Arrow benefit for Postgres requires the
 * native libadbc_driver_postgresql C library, loaded into Java via
 * either (a) the Foreign Function &amp; Memory API (JDK 22+ — we're
 * on 21) or (b) {@code adbc-driver-jni} + manual JNI bridge code.
 * Apache has NOT published a clean Java native-loader for ADBC
 * Postgres yet: Maven Central lists {@code adbc-driver-jdbc} (same
 * thing as arrow-jdbc, just packaged differently) and
 * {@code adbc-driver-jni} (the bridge), but no
 * {@code adbc-driver-postgresql} Java artifact. Native loading
 * remains a system-install-plus-JNI exercise. This test ISN'T
 * trying to prove the wire benefit — it's checking whether
 * "Arrow API surface, even atop row-shaped JDBC, with the recipe
 * applied" lands at parity-or-better with plain JDBC. If yes →
 * recipe is portable to any backend regardless of wire format. If
 * no → recipe only helps when the wire is already columnar (like
 * DuckDB's {@code arrowExportStream}).
 *
 * <h3>Skip conditions</h3>
 *
 * <ul>
 *   <li>{@code CALCITE_HARNESS_BACKEND} env var != {@code POSTGRES}
 *       — default {@code mvn test} skips this entirely.</li>
 *   <li>{@code sales_fact_1997} table missing — gracefully assume-skip
 *       so the test passes when Postgres is up but FoodMart hasn't
 *       been bootstrapped. See {@code docs/reports/postgres-foodmart-bootstrap.md}.</li>
 * </ul>
 *
 * <h3>Configuration knobs (env vars)</h3>
 *
 * <ul>
 *   <li>{@code MONDRIAN_PG_FACT_LIMIT} — row count for the read
 *       benchmark. Default {@value #DEFAULT_LIMIT}.</li>
 *   <li>{@code -Darrow.enable_null_check_for_get=false} — JVM flag,
 *       set on the {@code mvn} command line to halve the
 *       Float8Vector wrapper's per-call overhead.</li>
 * </ul>
 */
public class PostgresArrowFactReadSpikeTest {

    private static final int DEFAULT_LIMIT = 100_000;

    private DataSource ds;
    private BufferAllocator allocator;
    private int limit;

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue(
            "PostgresArrowFactReadSpikeTest skipped: "
                + "CALCITE_HARNESS_BACKEND != POSTGRES",
            HarnessBackend.current() == HarnessBackend.POSTGRES);
        ds = PostgresFoodMartDataSource.create();
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT COUNT(*) FROM information_schema.tables "
                 + "WHERE table_name = 'sales_fact_1997'"))
        {
            rs.next();
            int present = rs.getInt(1);
            Assume.assumeTrue(
                "sales_fact_1997 table missing — bootstrap per "
                    + "docs/reports/postgres-foodmart-bootstrap.md",
                present == 1);
        }
        allocator = new RootAllocator(Long.MAX_VALUE);

        String limitStr = System.getenv("MONDRIAN_PG_FACT_LIMIT");
        if (limitStr == null) {
            limitStr = System.getProperty(
                "mondrian.pg.fact.limit", String.valueOf(DEFAULT_LIMIT));
        }
        limit = Integer.parseInt(limitStr);
    }

    @After
    public void tearDown() {
        if (allocator != null) {
            allocator.close();
        }
    }

    /**
     * Correctness equivalence: JDBC and Arrow paths return the same
     * {@code SUM(store_sales)} from the same query.
     */
    @Test
    public void equivalence_jdbcAndArrowReturnSameSum() throws Exception {
        String sql = querySql(limit);

        double jdbcSum = sumStoreSalesJdbc(sql, /*fetchSize*/ 0);
        double arrowSum = sumStoreSalesArrow(sql, /*batchHint*/ -1, false);
        assertEquals(
            "JDBC and Arrow paths diverged on the same query",
            jdbcSum, arrowSum, 1e-3);
    }

    /**
     * Tuning matrix on a {@value #DEFAULT_LIMIT}-row scan. Prints a
     * results table to stderr per run.
     */
    @Test
    public void perfRawScan_jdbcVsArrow_tuningMatrix() throws Exception {
        String sql = querySql(limit);

        // Warmup
        sumStoreSalesJdbc(sql, 0);
        sumStoreSalesJdbc(sql, 1000);
        sumStoreSalesArrow(sql, -1, false);
        sumStoreSalesArrow(sql, -1, true);
        sumStoreSalesArrow(sql, limit, false);
        sumStoreSalesArrow(sql, limit, true);

        long jdbcDefault = time(() -> sumStoreSalesJdbc(sql, 0));
        long jdbcTuned = time(() -> sumStoreSalesJdbc(sql, 1000));
        long arrowDefault = time(() -> sumStoreSalesArrow(sql, -1, false));
        long arrowBypass = time(() -> sumStoreSalesArrow(sql, -1, true));
        long arrowSingleBatch = time(() -> sumStoreSalesArrow(sql, limit, false));
        long arrowBoth = time(() -> sumStoreSalesArrow(sql, limit, true));

        // Native ADBC Postgres path — needs libadbc_driver_postgresql.dylib
        // installed on system library path. Skip variant cleanly if not.
        Long adbcNativeWrapped = tryNativeAdbc(sql, false);
        Long adbcNativeBypass = tryNativeAdbc(sql, true);

        long baseline = jdbcDefault;

        System.err.println();
        System.err.printf(
            "[#36 PG raw-scan perf] limit=%d (lower is better for Arrow)%n",
            limit);
        printRow("jdbc default (fetchSize=0)         ", jdbcDefault, baseline);
        printRow("jdbc + fetchSize=1000               ", jdbcTuned, baseline);
        printRow("arrow-jdbc default (wrapper read)   ", arrowDefault, baseline);
        printRow("arrow-jdbc + bypass wrapper         ", arrowBypass, baseline);
        printRow("arrow-jdbc + single-batch config    ", arrowSingleBatch, baseline);
        printRow("arrow-jdbc + bypass + single batch  ", arrowBoth, baseline);
        if (adbcNativeWrapped != null) {
            printRow("ADBC native pg (wrapper read)       ", adbcNativeWrapped, baseline);
        } else {
            System.err.println(
                "  ADBC native pg (wrapper read)        SKIPPED — libadbc_driver_postgresql not loadable");
        }
        if (adbcNativeBypass != null) {
            printRow("ADBC native pg + bypass wrapper     ", adbcNativeBypass, baseline);
        }
        System.err.printf(
            "  (NULL_CHECKING_ENABLED = %s — set -Darrow.enable_null_check_for_get=false to compare)%n",
            NullCheckingForGet.NULL_CHECKING_ENABLED);
        System.err.println(
            "  arrow-jdbc rows go through row-shaped JDBC then convert "
            + "Java-side; ADBC native talks Postgres wire format directly "
            + "via libadbc_driver_postgresql + libpq.");
    }

    /**
     * Attempts the native ADBC Postgres path via the JNI bridge. Returns
     * elapsed nanos or {@code null} if the native lib isn't loadable
     * (e.g. libadbc_driver_postgresql.dylib not installed).
     */
    private Long tryNativeAdbc(String sql, boolean bypassWrapper) {
        try {
            sumStoreSalesNativeAdbc(sql, bypassWrapper);  // warmup
            long t0 = System.nanoTime();
            sumStoreSalesNativeAdbc(sql, bypassWrapper);
            return System.nanoTime() - t0;
        } catch (UnsatisfiedLinkError | NoClassDefFoundError missing) {
            System.err.println(
                "  ADBC native skip reason: " + missing.getMessage());
            return null;
        } catch (Exception probable) {
            String msg = probable.getMessage();
            System.err.println(
                "  ADBC native skip reason (" + probable.getClass().getSimpleName()
                    + "): " + msg);
            return null;
        }
    }

    private double sumStoreSalesNativeAdbc(String sql, boolean bypassWrapper)
        throws Exception
    {
        // Convert "jdbc:postgresql://host/db" → "postgresql://host/db"
        String jdbcUrl = mondrian.test.calcite.PostgresFoodMartDataSource.DEFAULT_URL;
        String adbcUri = jdbcUrl.startsWith("jdbc:")
            ? jdbcUrl.substring("jdbc:".length()) : jdbcUrl;

        // ADBC driver discovery uses manifest TOMLs by short name OR a
        // direct path to the .so/.dylib. Allow override via
        // ADBC_POSTGRES_DRIVER_LIB env var; default to the Homebrew
        // install path used by `brew install apache-arrow-adbc` + a
        // source-built libadbc_driver_postgresql.dylib copied alongside
        // the driver manager.
        String driverLib = System.getenv("ADBC_POSTGRES_DRIVER_LIB");
        if (driverLib == null || driverLib.isEmpty()) {
            driverLib =
                "/opt/homebrew/lib/libadbc_driver_postgresql.dylib";
        }
        Map<String, Object> params = new HashMap<>();
        params.put(JniDriver.PARAM_DRIVER.getKey(), driverLib);
        params.put(AdbcDriver.PARAM_URI.getKey(), adbcUri);

        double total = 0;
        // ServiceLoader registers factories by canonical class name,
        // not by short alias. Use the FQCN for the JNI driver factory.
        //
        // Manual close-in-reverse-order, not try-with-resources, because
        // the JNI bridge's auto-close of QueryResult also closes the
        // ArrowReader's backing ArrowArrayStream — and try-with-resources
        // then double-closes, throwing "ArrowArrayStream is already
        // closed". The manual order with quietClose() swallows that.
        AdbcDatabase db = null;
        AdbcConnection conn = null;
        AdbcStatement stmt = null;
        AdbcStatement.QueryResult qr = null;
        ArrowReader reader = null;
        try {
            db = AdbcDriverManager.getInstance()
                .connect(JniDriverFactory.class.getCanonicalName(),
                         allocator, params);
            conn = db.connect();
            stmt = conn.createStatement();
            stmt.setSqlQuery(sql);
            qr = stmt.executeQuery();
            reader = qr.getReader();
            while (reader.loadNextBatch()) {
                VectorSchemaRoot root = reader.getVectorSchemaRoot();
                Float8Vector amounts =
                    (Float8Vector) root.getVector("amount");
                int rowCount = root.getRowCount();
                if (bypassWrapper) {
                    ArrowBuf buf = amounts.getDataBuffer();
                    for (int i = 0; i < rowCount; i++) {
                        total += buf.getDouble((long) i << 3);
                    }
                } else {
                    for (int i = 0; i < rowCount; i++) {
                        total += amounts.get(i);
                    }
                }
            }
        } finally {
            quietClose(reader);
            quietClose(stmt);
            quietClose(conn);
            quietClose(db);
        }
        return total;
    }

    private static void quietClose(AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    private static void printRow(String label, long elapsed, long baseline) {
        System.err.printf(
            "  %-36s%7.1f ms   ratio %.2fx%n",
            label, elapsed / 1e6, (double) elapsed / baseline);
    }

    // ---------- helpers ----------

    private String querySql(int limit) {
        // store_sales is NUMERIC in Postgres FoodMart; cast to
        // DOUBLE PRECISION so arrow-jdbc produces a Float8Vector
        // (matches the DuckDB spike's column type for an apples-to-apples
        // tuning comparison). ORDER BY product_id pins a deterministic
        // row order so JDBC and Arrow paths read the same rows (without
        // ORDER BY, Postgres can return different 100k rows per query).
        return "SELECT store_id, store_sales::DOUBLE PRECISION AS amount "
            + "FROM sales_fact_1997 ORDER BY product_id LIMIT " + limit;
    }

    private double sumStoreSalesJdbc(String sql, int fetchSize)
        throws SQLException
    {
        double total = 0;
        try (Connection c = ds.getConnection())
        {
            // Postgres fetchSize requires auto-commit OFF + cursor mode.
            if (fetchSize > 0) {
                c.setAutoCommit(false);
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                if (fetchSize > 0) {
                    ps.setFetchSize(fetchSize);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    // Every shape in this file aliases the numeric measure
                    // column as "amount". Reading by name is shape-agnostic.
                    while (rs.next()) {
                        total += rs.getDouble("amount");
                    }
                }
            }
            if (fetchSize > 0) {
                c.commit();
            }
        }
        return total;
    }

    /**
     * @param batchHint  rows-per-batch hint passed to arrow-jdbc. {@code -1}
     *                   uses default. Larger batches amortise per-batch overhead.
     * @param bypassWrapper read via {@code ArrowBuf.getDouble} directly
     *                      instead of {@code Float8Vector.get}.
     */
    private double sumStoreSalesArrow(
        String sql, int batchHint, boolean bypassWrapper) throws Exception
    {
        double total = 0;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery())
        {
            JdbcToArrowConfig cfg = new JdbcToArrowConfigBuilder()
                .setAllocator(allocator)
                .setCalendar(Calendar.getInstance())
                .setTargetBatchSize(
                    batchHint > 0 ? batchHint : 1024)
                .build();
            ArrowVectorIterator it =
                JdbcToArrow.sqlToArrowVectorIterator(rs, cfg);
            try {
                while (it.hasNext()) {
                    VectorSchemaRoot root = it.next();
                    Float8Vector amounts =
                        (Float8Vector) root.getVector("amount");
                    int rowCount = root.getRowCount();
                    if (bypassWrapper) {
                        ArrowBuf buf = amounts.getDataBuffer();
                        for (int i = 0; i < rowCount; i++) {
                            total += buf.getDouble((long) i << 3);
                        }
                    } else {
                        for (int i = 0; i < rowCount; i++) {
                            total += amounts.get(i);
                        }
                    }
                    root.close();
                }
            } finally {
                it.close();
            }
        }
        return total;
    }

    /**
     * Corpus benchmark — companion to
     * <a href="https://articles.concepttocloud.com/p/swapping-mondrians-sql-emitter-for">the
     * Calcite-vs-legacy emitter blog post</a>. Where that post measured the
     * <em>planner</em> side (Mondrian's legacy SQL builder vs Calcite's
     * SqlDialect emission), this benchmark measures the <em>wire</em>
     * side (JDBC vs native ADBC) for the same Postgres FoodMart fixture
     * at the same scale (FACT_SCALE=1000, ~86.8M rows in sales_fact_1997).
     *
     * <p>Runs ~7 representative SQL shapes drawn from Mondrian's typical
     * fact-read patterns (raw scan, single-dim agg, multi-dim agg,
     * time-filtered, multi-measure, joined-with-dim, agg-table read).
     * For each shape: times JDBC default + the best native ADBC variant
     * (bypass wrapper + NULL_CHECKING_ENABLED defaults to whatever's
     * configured). Prints a per-shape comparison table + a geomean
     * summary matching the prior blog's methodology.
     *
     * <p>Skip-friendly like the rest of the file: needs
     * CALCITE_HARNESS_BACKEND=POSTGRES + sales_fact_1997 present +
     * libadbc_driver_postgresql.dylib loadable.
     */
    @Test
    public void perfCorpus_jdbcVsNativeAdbc_acrossShapes() throws Exception {
        Shape[] shapes = {
            new Shape("raw scan (LIMIT N)",
                "SELECT store_id, store_sales::DOUBLE PRECISION AS amount "
                + "FROM sales_fact_1997 ORDER BY product_id LIMIT " + limit),
            new Shape("single-dim agg (store)",
                "SELECT store_id, "
                + "SUM(store_sales::DOUBLE PRECISION) AS amount "
                + "FROM sales_fact_1997 GROUP BY store_id "
                + "ORDER BY store_id"),
            new Shape("multi-dim agg (store, time)",
                "SELECT store_id, time_id, "
                + "SUM(store_sales::DOUBLE PRECISION) AS amount "
                + "FROM sales_fact_1997 GROUP BY store_id, time_id "
                + "ORDER BY store_id, time_id"),
            new Shape("time-filtered single-dim",
                "SELECT store_id, "
                + "SUM(store_sales::DOUBLE PRECISION) AS amount "
                + "FROM sales_fact_1997 "
                + "WHERE time_id BETWEEN 366 AND 731 "
                + "GROUP BY store_id ORDER BY store_id"),
            new Shape("multi-measure single-dim",
                "SELECT store_id, "
                + "SUM(store_sales::DOUBLE PRECISION) AS amount, "
                + "SUM(unit_sales::DOUBLE PRECISION) AS units "
                + "FROM sales_fact_1997 GROUP BY store_id "
                + "ORDER BY store_id"),
            new Shape("joined-with-dim (year rollup)",
                "SELECT t.the_year, "
                + "SUM(f.store_sales::DOUBLE PRECISION) AS amount "
                + "FROM sales_fact_1997 f "
                + "JOIN time_by_day t ON f.time_id = t.time_id "
                + "GROUP BY t.the_year ORDER BY t.the_year"),
            new Shape("agg-table read (pre-rolled-up)",
                "SELECT product_category, gender, the_year, "
                + "SUM(store_sales::DOUBLE PRECISION) AS amount "
                + "FROM agg_g_ms_pcat_sales_fact_1997 "
                + "GROUP BY product_category, gender, the_year "
                + "ORDER BY product_category, gender, the_year")
        };

        // Pre-flight: native ADBC must be available for this to mean
        // anything. Skip the whole test if not (more useful than printing
        // a row of skips per shape).
        Long probe = tryNativeAdbc(shapes[0].sql, true);
        Assume.assumeTrue(
            "Native ADBC Postgres driver not loadable — see "
                + "/opt/homebrew/lib/libadbc_driver_postgresql.dylib + "
                + "ADBC_POSTGRES_DRIVER_LIB env var",
            probe != null);

        // Warmup all paths on all shapes
        for (Shape s : shapes) {
            sumStoreSalesJdbc(s.sql, 0);
            sumStoreSalesNativeAdbc(s.sql, true);
        }

        long[] jdbcTimes = new long[shapes.length];
        long[] arrowTimes = new long[shapes.length];

        for (int i = 0; i < shapes.length; i++) {
            // 3 timed runs per shape, take the min (less JIT/GC noise)
            long bestJdbc = Long.MAX_VALUE;
            long bestArrow = Long.MAX_VALUE;
            for (int rep = 0; rep < 3; rep++) {
                final Shape s = shapes[i];
                long tJ = time(() -> sumStoreSalesJdbc(s.sql, 0));
                long tA = time(() -> sumStoreSalesNativeAdbc(s.sql, true));
                if (tJ < bestJdbc) bestJdbc = tJ;
                if (tA < bestArrow) bestArrow = tA;
            }
            jdbcTimes[i] = bestJdbc;
            arrowTimes[i] = bestArrow;
        }

        System.err.println();
        System.err.printf(
            "[#36 PG corpus benchmark — companion to "
                + "concepttocloud.com Calcite-vs-legacy post]%n");
        System.err.printf(
            "Postgres FoodMart scale=1000 (~86.8M-row sales_fact_1997). "
                + "LIMIT=%d on raw-scan shape.%n",
            limit);
        System.err.printf(
            "Best of 3 timed runs per cell. Both paths use the tuning "
                + "recipe (native ADBC = bypass wrapper).%n%n");
        System.err.printf(
            "  %-40s %10s %10s %10s%n",
            "shape", "jdbc(ms)", "adbc(ms)", "adbc/jdbc");
        System.err.printf(
            "  %-40s %10s %10s %10s%n",
            "-----", "--------", "--------", "---------");

        double geomeanRatio = 1.0;
        int n = shapes.length;
        for (int i = 0; i < n; i++) {
            double ratio = (double) arrowTimes[i] / jdbcTimes[i];
            geomeanRatio *= ratio;
            System.err.printf(
                "  %-40s %10.1f %10.1f %10.3fx%n",
                shapes[i].label,
                jdbcTimes[i] / 1e6,
                arrowTimes[i] / 1e6,
                ratio);
        }
        geomeanRatio = Math.pow(geomeanRatio, 1.0 / n);
        System.err.printf(
            "%n  %-40s %10s %10s %10.3fx  ← Arrow / JDBC%n",
            "GEOMEAN across " + n + " shapes",
            "", "", geomeanRatio);
        System.err.printf(
            "  (NULL_CHECKING_ENABLED = %s)%n",
            NullCheckingForGet.NULL_CHECKING_ENABLED);
    }

    /** Named SQL shape for the corpus benchmark output. */
    private static final class Shape {
        final String label;
        final String sql;
        Shape(String label, String sql) {
            this.label = label;
            this.sql = sql;
        }
    }

    @FunctionalInterface
    private interface ThrowingDoubleSupplier {
        double get() throws Exception;
    }

    private static long time(ThrowingDoubleSupplier r) throws Exception {
        long s = System.nanoTime();
        double result = r.get();
        long elapsed = System.nanoTime() - s;
        if (Double.isNaN(result)) {
            throw new AssertionError("NaN result");
        }
        return elapsed;
    }
}

// End PostgresArrowFactReadSpikeTest.java
