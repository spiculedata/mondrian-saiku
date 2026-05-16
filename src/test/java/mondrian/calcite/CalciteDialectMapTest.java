package mondrian.calcite;

import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.dialect.DuckDBSqlDialect;
import org.apache.calcite.sql.dialect.ExasolSqlDialect;
import org.apache.calcite.sql.dialect.HiveSqlDialect;
import org.apache.calcite.sql.dialect.HsqldbSqlDialect;
import org.apache.calcite.sql.dialect.LucidDbSqlDialect;
import org.apache.calcite.sql.dialect.PhoenixSqlDialect;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.dialect.PrestoSqlDialect;
import org.apache.calcite.sql.dialect.RedshiftSqlDialect;
import org.apache.calcite.sql.dialect.SparkSqlDialect;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.junit.Assert.*;import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CalciteDialectMapTest {
    @Test public void hsqldbDialectMapsToHsqldb() {
        SqlDialect sd = CalciteDialectMap.forProductName("HSQL Database Engine");
        assertTrue(sd instanceof HsqldbSqlDialect);
    }
    @Test public void caseInsensitive() {
        SqlDialect sd = CalciteDialectMap.forProductName("hsql database engine");
        assertTrue(sd instanceof HsqldbSqlDialect);
    }
    @Test public void unknownProductThrows() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> CalciteDialectMap.forProductName("Frobnitz 9.9"));
    }
    @Test public void nullThrows() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> CalciteDialectMap.forProductName(null));
    }

    // --- Task Z: Postgres mappings ---

    @Test public void postgresProductMapsToPostgres() {
        SqlDialect sd = CalciteDialectMap.forProductName("PostgreSQL");
        assertTrue(sd instanceof PostgresqlSqlDialect);
    }

    @Test public void postgresProductLowercaseMapsToPostgres() {
        SqlDialect sd = CalciteDialectMap.forProductName("postgresql");
        assertTrue(sd instanceof PostgresqlSqlDialect);
    }

    @Test public void postgresDataSourceRoundTrip() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        SqlDialect sd = CalciteDialectMap.forDataSource(ds);
        assertTrue(sd instanceof PostgresqlSqlDialect);
    }

    @Test public void hsqldbDataSourceRoundTripUnchanged() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("HSQL Database Engine");
        SqlDialect sd = CalciteDialectMap.forDataSource(ds);
        assertTrue(sd instanceof HsqldbSqlDialect);
    }

    /**
     * Calcite's stock PostgresqlSqlDialect already uses {@code "} to quote
     * identifiers — no subclass override needed. Confirms that SQL emitted
     * via this dialect will double-quote identifiers to match the FoodMart
     * fixture's case-sensitive column/table names.
     */
    @Test public void postgresDialectQuotesIdentifiersWithDoubleQuote() {
        SqlDialect pg = PostgresqlSqlDialect.DEFAULT;
        StringBuilder buf = new StringBuilder();
        pg.quoteIdentifier(buf, "sales_fact_1997");
        assertEquals("\"sales_fact_1997\"", buf.toString());
    }

    // --- Hand-curated coverage guards ---
    //
    // Each test below asserts that a real JDBC driver's product-name
    // string maps to the expected Calcite dialect family. The point is
    // not to verify Calcite's internals — it's to guard against a future
    // refactor accidentally removing a forProductNameOrNull() branch
    // and silently re-introducing the legacy-fallback gap for that
    // database. The product-name strings used here are the literal
    // values reported by the official drivers as of 2026-05.

    @Test public void duckdbDriverStringMapsToDuckDbDialect() {
        SqlDialect sd = CalciteDialectMap.forProductName("DuckDB");
        assertTrue(sd instanceof DuckDBSqlDialect);
    }

    @Test public void redshiftDriverStringMapsToRedshiftDialect() {
        SqlDialect sd = CalciteDialectMap.forProductName("Amazon Redshift");
        assertTrue(sd instanceof RedshiftSqlDialect);
    }

    @Test public void hiveDriverStringMapsToHiveDialect() {
        SqlDialect sd = CalciteDialectMap.forProductName("Apache Hive");
        assertTrue(sd instanceof HiveSqlDialect);
    }

    @Test public void trinoDriverStringMapsToPrestoDialect() {
        // Trino is the rename of Presto; PrestoSqlDialect is the right
        // emit target for both. Calcite's own factory matches "PRESTO"
        // but not "TRINO" — this hand-curated entry closes that gap.
        SqlDialect sd = CalciteDialectMap.forProductName("Trino");
        assertTrue(sd instanceof PrestoSqlDialect);
    }

    @Test public void exasolDriverStringMapsToExasolDialect() {
        SqlDialect sd = CalciteDialectMap.forProductName("EXASolution");
        assertTrue(sd instanceof ExasolSqlDialect);
    }

    @Test public void sparkDriverStringMapsToSparkDialect() {
        // Spark's JDBC driver returns "Spark SQL", Calcite's factory
        // only matches the exact "SPARK" case.
        SqlDialect sd = CalciteDialectMap.forProductName("Spark SQL");
        assertTrue(sd instanceof SparkSqlDialect);
    }

    @Test public void phoenixDriverStringMapsToPhoenixDialect() {
        SqlDialect sd = CalciteDialectMap.forProductName("Apache Phoenix");
        assertTrue(sd instanceof PhoenixSqlDialect);
    }

    @Test public void luciddbDriverStringMapsToLucidDbDialect() {
        SqlDialect sd = CalciteDialectMap.forProductName("LucidDB");
        assertTrue(sd instanceof LucidDbSqlDialect);
    }

    @Test public void duckdbDataSourceRoundTripUnchanged() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("DuckDB");
        SqlDialect sd = CalciteDialectMap.forDataSource(ds);
        assertTrue(sd instanceof DuckDBSqlDialect);
    }
}
