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

import mondrian.olap.Connection;
import mondrian.olap.DriverManager;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.olap.Util;
import mondrian.rolap.RolapConnectionProperties;
import mondrian.test.FoodMartHsqldbBootstrap;
import mondrian.test.TestContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faithful reproduction of the customer GDELT cube for issue #89: the real
 * two-fact ({@code fact_events}, {@code fact_mentions}) multi-MeasureGroup
 * schema with conformed dimensions (Event Root, QuadClass, Action Country)
 * linking via {@code ForeignKeyLink} into both groups on string code keys,
 * a conformed Date dimension that links via a <em>different</em> FK column
 * per group ({@code date_key} vs {@code mention_date_key}), and degenerate
 * {@code FactLink} dimensions.
 *
 * <p>The GDELT tables are created in the shared FoodMart HSQLDB with a few
 * representative rows, then the cube is mounted via {@code CatalogContent}.
 * Each query runs on both backends; the result grids must match.
 *
 * <p>Covers both the inline-set NON EMPTY shape (fixed in 4.8.1.18) and the
 * {@code WITH SET [~ROWS] AS {...} ... NON EMPTY [~ROWS]} named-set shape
 * Saiku Studio emits (the #89 reopen — still empty in 4.8.1.18).
 */
public class GdeltSchemaReproTest {

    private static final String SCHEMA =
        "<Schema name='GDELT' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='dim_date'/>\n"
        + "    <Table name='dim_event_root'/>\n"
        + "    <Table name='dim_quadclass'/>\n"
        + "    <Table name='dim_country'/>\n"
        + "    <Table name='fact_events'/>\n"
        + "    <Table name='fact_mentions'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <Dimension name='Date' table='dim_date' key='Date Id' type='TIME'>\n"
        + "    <Attributes>\n"
        + "      <Attribute name='Date Id' hasHierarchy='false'>\n"
        + "        <Key><Column name='date_key'/></Key>\n"
        + "      </Attribute>\n"
        + "      <Attribute name='Year' levelType='TimeYears' hasHierarchy='false'>\n"
        + "        <Key><Column name='year'/></Key>\n"
        + "        <Name><Column name='year_name'/></Name>\n"
        + "      </Attribute>\n"
        + "    </Attributes>\n"
        + "    <Hierarchies>\n"
        + "      <Hierarchy name='Calendar' allMemberName='All Dates'>\n"
        + "        <Level attribute='Year'/>\n"
        + "      </Hierarchy>\n"
        + "    </Hierarchies>\n"
        + "  </Dimension>\n"
        + "  <Dimension name='Event Root' table='dim_event_root' key='Event Root'>\n"
        + "    <Attributes>\n"
        + "      <Attribute name='Event Root'>\n"
        + "        <Key><Column name='event_root_code'/></Key>\n"
        + "        <Name><Column name='event_root_name'/></Name>\n"
        + "      </Attribute>\n"
        + "    </Attributes>\n"
        + "  </Dimension>\n"
        + "  <Dimension name='QuadClass' table='dim_quadclass' key='QuadClass'>\n"
        + "    <Attributes>\n"
        + "      <Attribute name='QuadClass'>\n"
        + "        <Key><Column name='quadclass_key'/></Key>\n"
        + "        <Name><Column name='quadclass_name'/></Name>\n"
        + "      </Attribute>\n"
        + "    </Attributes>\n"
        + "  </Dimension>\n"
        + "  <Dimension name='Country' table='dim_country' key='Country'>\n"
        + "    <Attributes>\n"
        + "      <Attribute name='Country'>\n"
        + "        <Key><Column name='country_code'/></Key>\n"
        + "        <Name><Column name='country_name'/></Name>\n"
        + "      </Attribute>\n"
        + "    </Attributes>\n"
        + "  </Dimension>\n"
        + "  <Cube name='GDELT'>\n"
        + "    <Dimensions>\n"
        + "      <Dimension source='Date'/>\n"
        + "      <Dimension source='Event Root'/>\n"
        + "      <Dimension source='QuadClass'/>\n"
        + "      <Dimension source='Country' name='Action Country'/>\n"
        + "      <Dimension name='Goldstein Bucket' table='fact_events'"
        + " key='Goldstein Bucket'>\n"
        + "        <Attributes>\n"
        + "          <Attribute name='Goldstein Bucket'"
        + " keyColumn='goldstein_bucket'/>\n"
        + "        </Attributes>\n"
        + "      </Dimension>\n"
        + "    </Dimensions>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='Events' table='fact_events'>\n"
        + "        <Measures>\n"
        + "          <Measure name='Event Count' column='event_count'"
        + " aggregator='sum'/>\n"
        + "          <Measure name='Avg Tone' column='avg_tone'"
        + " aggregator='avg'/>\n"
        + "        </Measures>\n"
        + "        <DimensionLinks>\n"
        + "          <ForeignKeyLink dimension='Date'"
        + " foreignKeyColumn='date_key'/>\n"
        + "          <ForeignKeyLink dimension='Event Root'"
        + " foreignKeyColumn='event_root_code'/>\n"
        + "          <ForeignKeyLink dimension='QuadClass'"
        + " foreignKeyColumn='quadclass_key'/>\n"
        + "          <ForeignKeyLink dimension='Action Country'"
        + " foreignKeyColumn='action_country'/>\n"
        + "          <FactLink dimension='Goldstein Bucket'/>\n"
        + "        </DimensionLinks>\n"
        + "      </MeasureGroup>\n"
        + "      <MeasureGroup name='Mentions' table='fact_mentions'>\n"
        + "        <Measures>\n"
        + "          <Measure name='Mention Count' column='mention_count'"
        + " aggregator='sum'/>\n"
        + "        </Measures>\n"
        + "        <DimensionLinks>\n"
        + "          <ForeignKeyLink dimension='Date'"
        + " foreignKeyColumn='mention_date_key'/>\n"
        + "          <ForeignKeyLink dimension='Event Root'"
        + " foreignKeyColumn='event_root_code'/>\n"
        + "          <ForeignKeyLink dimension='QuadClass'"
        + " foreignKeyColumn='quadclass_key'/>\n"
        + "          <ForeignKeyLink dimension='Action Country'"
        + " foreignKeyColumn='action_country'/>\n"
        + "          <NoLink dimension='Goldstein Bucket'/>\n"
        + "        </DimensionLinks>\n"
        + "      </MeasureGroup>\n"
        + "    </MeasureGroups>\n"
        + "  </Cube>\n"
        + "</Schema>\n";

    // HSQLDB folds unquoted identifiers to UPPERCASE; Mondrian references
    // them quoted-lowercase ("dim_date"."date_key"), matching FoodMart's
    // convention. So every table and column name is quoted lowercase here.
    private static final String[] DDL = {
        "DROP TABLE \"fact_events\" IF EXISTS",
        "DROP TABLE \"fact_mentions\" IF EXISTS",
        "DROP TABLE \"dim_date\" IF EXISTS",
        "DROP TABLE \"dim_event_root\" IF EXISTS",
        "DROP TABLE \"dim_quadclass\" IF EXISTS",
        "DROP TABLE \"dim_country\" IF EXISTS",
        "CREATE TABLE \"dim_date\" (\"date_key\" INTEGER, \"year\" INTEGER,"
            + " \"year_name\" VARCHAR(16))",
        "CREATE TABLE \"dim_event_root\" (\"event_root_code\" VARCHAR(8),"
            + " \"event_root_name\" VARCHAR(64))",
        "CREATE TABLE \"dim_quadclass\" (\"quadclass_key\" INTEGER,"
            + " \"quadclass_name\" VARCHAR(64))",
        "CREATE TABLE \"dim_country\" (\"country_code\" VARCHAR(8),"
            + " \"country_name\" VARCHAR(64))",
        "CREATE TABLE \"fact_events\" (\"event_count\" INTEGER,"
            + " \"avg_tone\" DOUBLE, \"date_key\" INTEGER,"
            + " \"event_root_code\" VARCHAR(8), \"quadclass_key\" INTEGER,"
            + " \"action_country\" VARCHAR(8),"
            + " \"goldstein_bucket\" VARCHAR(16))",
        "CREATE TABLE \"fact_mentions\" (\"mention_count\" INTEGER,"
            + " \"mention_date_key\" INTEGER, \"event_root_code\" VARCHAR(8),"
            + " \"quadclass_key\" INTEGER, \"action_country\" VARCHAR(8))",
        // dims
        "INSERT INTO \"dim_date\" VALUES (20150101, 2015, '2015')",
        "INSERT INTO \"dim_date\" VALUES (20160101, 2016, '2016')",
        "INSERT INTO \"dim_event_root\" VALUES ('010', 'Make statement')",
        "INSERT INTO \"dim_event_root\" VALUES ('020', 'Appeal')",
        "INSERT INTO \"dim_event_root\" VALUES ('030', 'Express cooperation')",
        "INSERT INTO \"dim_quadclass\" VALUES (1, 'Verbal Cooperation')",
        "INSERT INTO \"dim_quadclass\" VALUES (2, 'Material Conflict')",
        "INSERT INTO \"dim_country\" VALUES ('US', 'United States')",
        "INSERT INTO \"dim_country\" VALUES ('GB', 'United Kingdom')",
        // fact_events: event roots 010, 020 appear (not 030)
        "INSERT INTO \"fact_events\""
            + " VALUES (5, 1.5, 20150101, '010', 1, 'US', 'pos')",
        "INSERT INTO \"fact_events\""
            + " VALUES (3, -2.0, 20150101, '020', 2, 'GB', 'neg')",
        "INSERT INTO \"fact_events\""
            + " VALUES (7, 0.5, 20160101, '010', 1, 'US', 'pos')",
        // fact_mentions: event roots 010, 030 appear
        "INSERT INTO \"fact_mentions\" VALUES (9, 20150101, '010', 1, 'US')",
        "INSERT INTO \"fact_mentions\" VALUES (4, 20160101, '030', 2, 'GB')",
    };

    private static Connection legacyConn;
    private static Connection calciteConn;

    @BeforeAll
    public static void boot() throws Exception {
        FoodMartHsqldbBootstrap.ensureExtracted();
        Util.PropertyList base =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        String jdbc = base.get("Jdbc");
        createGdeltTables(jdbc, base.get("JdbcUser"), base.get("JdbcPassword"));
        legacyConn = newConnection();
        calciteConn = newConnection();
    }

    @AfterAll
    public static void close() {
        if (legacyConn != null) {
            legacyConn.close();
            legacyConn = null;
        }
        if (calciteConn != null) {
            calciteConn.close();
            calciteConn = null;
        }
    }

    private static void createGdeltTables(
        String jdbc, String user, String password) throws Exception
    {
        try (java.sql.Connection c =
                 java.sql.DriverManager.getConnection(
                     jdbc, user, password);
             Statement st = c.createStatement())
        {
            for (String sql : DDL) {
                st.execute(sql);
            }
        }
    }

    private static Connection newConnection() {
        Util.PropertyList props =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        props.put("UseSchemaPool", "false");
        props.put(RolapConnectionProperties.CatalogContent.name(), SCHEMA);
        props.remove(RolapConnectionProperties.Catalog.name());
        return DriverManager.getConnection(props, null, null);
    }

    private static int rowCount(Result r) {
        return r.getAxes().length < 2
            ? 0 : r.getAxes()[1].getPositions().size();
    }

    private void assertParity(String label, String mdx) {
        System.setProperty("mondrian.backend", "legacy");
        Result legacy;
        try {
            legacy = legacyConn.execute(legacyConn.parseQuery(mdx));
        } finally {
            System.clearProperty("mondrian.backend");
        }
        System.setProperty("mondrian.backend", "calcite");
        Result calcite;
        try {
            calcite = calciteConn.execute(calciteConn.parseQuery(mdx));
        } finally {
            System.clearProperty("mondrian.backend");
        }
        assertTrue(
            rowCount(legacy) > 0,
            label + ": legacy must return rows (fixture sanity)");
        assertEquals(
            TestContext.toString(legacy),
            TestContext.toString(calcite),
            label + ": Calcite grid must match legacy");
    }

    /** Inline-set NON EMPTY — fixed in 4.8.1.18. */
    @Test
    public void eventRootInlineSet() {
        assertParity("inline",
            "SELECT NON EMPTY {[Measures].[Event Count]} ON COLUMNS,\n"
            + "  NON EMPTY {[Event Root].[Event Root].[Event Root].Members}"
            + " ON ROWS\n"
            + "FROM [GDELT]");
    }

    /** Named-set NON EMPTY — Saiku Studio's shape (#89 reopen). */
    @Test
    public void eventRootNamedSet() {
        assertParity("named-set",
            "WITH SET [~ROWS] AS"
            + " {[Event Root].[Event Root].[Event Root].Members}\n"
            + "SELECT NON EMPTY {[Measures].[Event Count]} ON COLUMNS,\n"
            + "  NON EMPTY [~ROWS] ON ROWS\n"
            + "FROM [GDELT]");
    }

    /**
     * Cross-measure-group query (Event Count from the Events group +
     * Mention Count from the Mentions group, by the conformed Event Root
     * dimension). The Calcite tuple-read translator declines this
     * virtual-cube UNION shape (UnsupportedTranslation: "spans multiple
     * measure groups") and falls back to legacy, which emits a standard
     * SQL UNION of per-fact member lists; the per-measure cell values load
     * as two single-fact segment queries. This guards that the fallback
     * keeps producing legacy-equivalent results — the shape that surfaced
     * the MotherDuck "syntax error at or near union" before 4.8.1.19.
     */
    @Test
    public void crossMeasureGroup() {
        CalcitePlannerAdapters.resetUnsupportedCount();
        assertParity("cross-mg",
            "SELECT {[Measures].[Event Count], [Measures].[Mention Count]}"
            + " ON COLUMNS,\n"
            + "  NON EMPTY [Event Root].[Event Root].[Event Root].Members"
            + " ON ROWS\n"
            + "FROM [GDELT]");
        // The Calcite path must translate the cross-measure-group UNION
        // natively — no fallback to legacy SQL for the tuple read.
        assertEquals(
            0L, CalcitePlannerAdapters.tupleReadUnsupportedCount(),
            "cross-measure-group tuple read must not fall back to legacy");
    }

    /** Named-set on the Date dimension (different FK column per group). */
    @Test
    public void dateNamedSet() {
        assertParity("date-named-set",
            "WITH SET [~ROWS] AS"
            + " {[Date].[Calendar].[Year].Members}\n"
            + "SELECT NON EMPTY {[Measures].[Event Count]} ON COLUMNS,\n"
            + "  NON EMPTY [~ROWS] ON ROWS\n"
            + "FROM [GDELT]");
    }
}
