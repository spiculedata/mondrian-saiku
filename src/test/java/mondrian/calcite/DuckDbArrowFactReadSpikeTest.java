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

import mondrian.rolap.agg.ArrowDoubleSegmentVector;
import mondrian.spi.DoubleSegmentVector;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;

/**
 * #36 Phase 0 spike — proves Arrow can flow from DuckDB into Mondrian's
 * {@link mondrian.spi.SegmentBody} (via {@link ArrowDoubleSegmentVector})
 * without round-tripping through JDBC's row-shaped {@code ResultSet}.
 *
 * <p>DuckDB is in-process so the wire isn't the bottleneck — this
 * spike validates the <strong>plumbing</strong> end-to-end and
 * produces honest timing data for a workload where JDBC has nothing
 * to do but JNI-cross. The same plumbing applies to cloud warehouses
 * (Snowflake, BigQuery, Databricks via ADBC) where the wire benefit
 * IS large; this spike is the credible foundation for that future work.
 *
 * <h3>What the tests do</h3>
 *
 * <ol>
 *   <li>{@code equivalence}: same aggregation query through JDBC and
 *       Arrow paths produces identical results.</li>
 *   <li>{@code arrowDirectlyPopulatesArrowDoubleSegmentVector}: extracts
 *       a {@link Float8Vector} from the DuckDB Arrow stream and feeds
 *       it into Mondrian's {@link ArrowDoubleSegmentVector} — proving
 *       the end-to-end story (DB Arrow → Mondrian segment cache).</li>
 *   <li>{@code perfRawScan_jdbcVsArrow}: times a 100k-row raw scan via
 *       both paths. Surfaces the per-row JDBC overhead vs Arrow's
 *       columnar batch read.</li>
 *   <li>{@code perfAggregation_jdbcVsArrow}: times a 10-row GROUP BY
 *       aggregation via both paths. Result is small so transfer cost
 *       is dwarfed by query execution — sanity that the Arrow path
 *       doesn't pessimise tiny result sets.</li>
 * </ol>
 */
public class DuckDbArrowFactReadSpikeTest {

    private Path dbFile;
    private DataSource ds;
    private BufferAllocator allocator;

    private static final int FACT_ROWS = 100_000;
    private static final int N_REGIONS = 10;

    @Before
    public void setUp() throws Exception {
        dbFile = Files.createTempFile(
            "duckarrow_" + UUID.randomUUID().toString().substring(0, 8),
            ".duckdb");
        Files.deleteIfExists(dbFile);
        ds = new SimpleDuckDbDataSource(
            "jdbc:duckdb:" + dbFile.toAbsolutePath());
        allocator = new RootAllocator(Long.MAX_VALUE);

        try (Connection c = ds.getConnection();
             Statement s = c.createStatement())
        {
            s.execute(
                "CREATE TABLE sales ("
                + "  id INTEGER, "
                + "  region VARCHAR, "
                + "  amount DOUBLE)");
            s.execute(
                "INSERT INTO sales "
                + "SELECT range AS id, "
                + "  'r' || (range % " + N_REGIONS + ")::VARCHAR AS region, "
                + "  (range * 1.5)::DOUBLE AS amount "
                + "FROM range(" + FACT_ROWS + ")");
        }
    }

    @After
    public void tearDown() throws Exception {
        if (allocator != null) {
            allocator.close();
        }
        ds = null;
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
            Files.deleteIfExists(
                dbFile.resolveSibling(dbFile.getFileName() + ".wal"));
        }
    }

    @Test
    public void equivalence_jdbcAndArrowProduceSameAggregates()
        throws Exception
    {
        String sql =
            "SELECT region, SUM(amount) AS total "
            + "FROM sales GROUP BY region ORDER BY region";

        Map<String, Double> viaJdbc = readAggregatesViaJdbc(sql);
        Map<String, Double> viaArrow = readAggregatesViaArrow(sql);

        assertEquals(
            "JDBC and Arrow paths returned different aggregate sets",
            viaJdbc, viaArrow);
        assertEquals(
            "expected " + N_REGIONS + " regions in the result",
            N_REGIONS, viaArrow.size());
    }

    @Test
    public void arrowDirectlyPopulatesArrowDoubleSegmentVector()
        throws Exception
    {
        String sql =
            "SELECT SUM(amount) AS total "
            + "FROM sales "
            + "GROUP BY region "
            + "ORDER BY region";

        java.util.ArrayList<Double> rows = new java.util.ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql))
        {
            ResultSet rs = ps.executeQuery();
            ArrowReader reader = (ArrowReader)
                rs.getClass()
                    .getMethod("arrowExportStream", Object.class, long.class)
                    .invoke(rs, allocator, 1024L);
            try {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    Float8Vector totals =
                        (Float8Vector) root.getVector(0);
                    for (int i = 0; i < root.getRowCount(); i++) {
                        rows.add(totals.get(i));
                    }
                }
            } finally {
                reader.close();
            }
        }

        double[] values = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            values[i] = rows.get(i);
        }
        DoubleSegmentVector segvec =
            new ArrowDoubleSegmentVector(values, new BitSet(values.length));
        assertEquals(N_REGIONS, segvec.size());

        // Cross-validate against the JDBC path
        Map<String, Double> jdbcAgg = readAggregatesViaJdbc(
            "SELECT region, SUM(amount) AS total "
            + "FROM sales GROUP BY region ORDER BY region");

        int idx = 0;
        for (Map.Entry<String, Double> e : jdbcAgg.entrySet()) {
            assertEquals(
                "row " + idx + " (region=" + e.getKey()
                    + ") mismatch between JDBC and ArrowDoubleSegmentVector",
                e.getValue(), segvec.getDouble(idx), 1e-9);
            idx++;
        }
    }

    @Test
    public void perfRawScan_jdbcVsArrow() throws Exception {
        String sql = "SELECT id, amount FROM sales";

        // Warmup
        sumRawScanJdbc(sql);
        sumRawScanArrow(sql);

        long t0 = System.nanoTime();
        double jdbcSum = sumRawScanJdbc(sql);
        long jdbcElapsed = System.nanoTime() - t0;

        long t1 = System.nanoTime();
        double arrowSum = sumRawScanArrow(sql);
        long arrowElapsed = System.nanoTime() - t1;

        assertEquals(
            "sums diverged between JDBC and Arrow on raw scan",
            jdbcSum, arrowSum, 1e-6);

        double ratio = (double) arrowElapsed / (double) jdbcElapsed;
        System.err.printf(
            "[#36 raw-scan perf] rows=%d  jdbc=%.1fms  arrow=%.1fms  "
                + "arrow/jdbc=%.2fx  (lower is better for Arrow)%n",
            FACT_ROWS, jdbcElapsed / 1e6, arrowElapsed / 1e6, ratio);
    }

    @Test
    public void perfAggregation_jdbcVsArrow() throws Exception {
        String sql =
            "SELECT region, SUM(amount) AS total "
            + "FROM sales GROUP BY region";

        // Warmup
        readAggregatesViaJdbc(sql);
        readAggregatesViaArrow(sql);

        long t0 = System.nanoTime();
        Map<String, Double> viaJdbc = readAggregatesViaJdbc(sql);
        long jdbcElapsed = System.nanoTime() - t0;

        long t1 = System.nanoTime();
        Map<String, Double> viaArrow = readAggregatesViaArrow(sql);
        long arrowElapsed = System.nanoTime() - t1;

        assertEquals(viaJdbc, viaArrow);

        double ratio = (double) arrowElapsed / (double) jdbcElapsed;
        System.err.printf(
            "[#36 agg perf]      rows=%d  jdbc=%.1fms  arrow=%.1fms  "
                + "arrow/jdbc=%.2fx%n",
            viaArrow.size(), jdbcElapsed / 1e6, arrowElapsed / 1e6, ratio);
    }

    // ---------- helpers ----------

    private Map<String, Double> readAggregatesViaJdbc(String sql)
        throws SQLException
    {
        Map<String, Double> out = new LinkedHashMap<>();
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql))
        {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getDouble(2));
            }
        }
        return out;
    }

    private Map<String, Double> readAggregatesViaArrow(String sql)
        throws Exception
    {
        Map<String, Double> out = new LinkedHashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql))
        {
            ResultSet rs = ps.executeQuery();
            ArrowReader reader = (ArrowReader)
                rs.getClass()
                    .getMethod("arrowExportStream", Object.class, long.class)
                    .invoke(rs, allocator, 1024L);
            try {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    VarCharVector regions = (VarCharVector) root.getVector(0);
                    Float8Vector totals = (Float8Vector) root.getVector(1);
                    int rowCount = root.getRowCount();
                    for (int i = 0; i < rowCount; i++) {
                        String region = new String(
                            regions.get(i), StandardCharsets.UTF_8);
                        out.put(region, totals.get(i));
                    }
                }
            } finally {
                reader.close();
            }
        }
        return out;
    }

    private double sumRawScanJdbc(String sql) throws SQLException {
        double total = 0;
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql))
        {
            while (rs.next()) {
                total += rs.getDouble(2);
            }
        }
        return total;
    }

    private double sumRawScanArrow(String sql) throws Exception {
        double total = 0;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql))
        {
            ResultSet rs = ps.executeQuery();
            ArrowReader reader = (ArrowReader)
                rs.getClass()
                    .getMethod("arrowExportStream", Object.class, long.class)
                    .invoke(rs, allocator, 8192L);
            try {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    Float8Vector amounts = (Float8Vector) root.getVector(1);
                    int rowCount = root.getRowCount();
                    for (int i = 0; i < rowCount; i++) {
                        total += amounts.get(i);
                    }
                }
            } finally {
                reader.close();
            }
        }
        return total;
    }

    // ---------- tiny DataSource shim ----------

    private static final class SimpleDuckDbDataSource implements DataSource {
        private final String url;
        SimpleDuckDbDataSource(String url) { this.url = url; }
        @Override public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }
        @Override public Connection getConnection(String u, String p)
            throws SQLException
        {
            return DriverManager.getConnection(url);
        }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger()
            throws SQLFeatureNotSupportedException
        {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}

// End DuckDbArrowFactReadSpikeTest.java
