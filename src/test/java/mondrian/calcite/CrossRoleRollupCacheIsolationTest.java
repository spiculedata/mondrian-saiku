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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #106 SECURITY (DB-driven, caching ON): two roles share one schema and one
 * value cache in a single JVM. A FULL-rollup-policy role sees the TRUE parent
 * total; a PARTIAL-rollup-policy role sees only the sum of its VISIBLE children.
 * Querying the same parent cell under both roles must NOT let the partial role
 * be served the full role's cached total (or vice-versa) — proving no cross-role
 * disclosure through the aggregate value cache.
 *
 * <p>This locks in the audit's "safe by construction" finding (rollup is applied
 * above the cached cell, and the cache key reflects the role's visible-member
 * set) so a future cache-key refactor cannot silently re-open it. Caching is
 * intentionally left ON ({@code UseSchemaPool=false} only) and both roles run in
 * either order to surface any bleed.
 */
public class CrossRoleRollupCacheIsolationTest {

    private static final String[] DDL = {
        "DROP TABLE IF EXISTS \"cr_sales\"",
        // One parent (All) over three children: EAST=100, WEST=50, SOUTH=10.
        // True total = 160. The partial role sees only EAST + WEST = 150.
        "CREATE TABLE \"cr_sales\" (\"region\" VARCHAR(8), \"amount\" INTEGER)",
        "INSERT INTO \"cr_sales\" VALUES"
            + " ('EAST',100),('WEST',50),('SOUTH',10)",
    };

    private static final String SCHEMA =
        "<Schema name='CR' metamodelVersion='4.0'>\n"
        + "  <PhysicalSchema>\n"
        + "    <Table name='cr_sales'/>\n"
        + "  </PhysicalSchema>\n"
        + "  <Dimension name='Region' table='cr_sales' key='Region'>\n"
        + "    <Attributes><Attribute name='Region' hasHierarchy='true'>\n"
        + "      <Key><Column name='region'/></Key>\n"
        + "    </Attribute></Attributes>\n"
        + "  </Dimension>\n"
        + "  <Cube name='Sales'>\n"
        + "    <Dimensions><Dimension source='Region'/></Dimensions>\n"
        + "    <MeasureGroups>\n"
        + "      <MeasureGroup name='S' table='cr_sales'>\n"
        + "        <Measures>\n"
        + "          <Measure name='Amount' column='amount'"
        + " aggregator='sum'/>\n"
        + "        </Measures>\n"
        + "        <DimensionLinks>\n"
        + "          <FactLink dimension='Region'/>\n"
        + "        </DimensionLinks>\n"
        + "      </MeasureGroup>\n"
        + "    </MeasureGroups>\n"
        + "  </Cube>\n"
        // FullRole: custom access to EAST+WEST but FULL rollup => parent shows
        // the TRUE total over ALL children (160), not just the visible ones.
        + "  <Role name='FullRole'>\n"
        + "    <SchemaGrant access='all'>\n"
        + "      <CubeGrant cube='Sales' access='all'>\n"
        + "        <HierarchyGrant hierarchy='[Region].[Region]'"
        + " access='custom' rollupPolicy='full'>\n"
        + "          <MemberGrant member='[Region].[EAST]' access='all'/>\n"
        + "          <MemberGrant member='[Region].[WEST]' access='all'/>\n"
        + "        </HierarchyGrant>\n"
        + "      </CubeGrant>\n"
        + "    </SchemaGrant>\n"
        + "  </Role>\n"
        // PartialRole: identical visible members but PARTIAL rollup => parent
        // shows only the sum of VISIBLE children (EAST+WEST=150).
        + "  <Role name='PartialRole'>\n"
        + "    <SchemaGrant access='all'>\n"
        + "      <CubeGrant cube='Sales' access='all'>\n"
        + "        <HierarchyGrant hierarchy='[Region].[Region]'"
        + " access='custom' rollupPolicy='partial'>\n"
        + "          <MemberGrant member='[Region].[EAST]' access='all'/>\n"
        + "          <MemberGrant member='[Region].[WEST]' access='all'/>\n"
        + "        </HierarchyGrant>\n"
        + "      </CubeGrant>\n"
        + "    </SchemaGrant>\n"
        + "  </Role>\n"
        + "</Schema>\n";

    private static final String MDX =
        "SELECT {[Measures].[Amount]} ON COLUMNS FROM [Sales]";

    private static final String H2_URL =
        "jdbc:h2:mem:cr_e2e;DB_CLOSE_DELAY=-1";

    @BeforeAll
    public static void boot() throws Exception {
        mondrian.test.FoodMartHsqldbBootstrap.ensureExtracted();
        Class.forName("org.h2.Driver");
        try (java.sql.Connection c =
                 java.sql.DriverManager.getConnection(H2_URL, "sa", "");
             Statement st = c.createStatement())
        {
            for (String sql : DDL) {
                st.execute(sql);
            }
        }
        mondrian.rolap.agg.SegmentLoader.clearCalcitePlannerCache();
    }

    private static Connection connect(String role) {
        Util.PropertyList p = new Util.PropertyList();
        p.put("Provider", "mondrian");
        p.put(RolapConnectionProperties.Jdbc.name(), H2_URL);
        p.put(RolapConnectionProperties.JdbcDrivers.name(), "org.h2.Driver");
        p.put(RolapConnectionProperties.JdbcUser.name(), "sa");
        p.put(RolapConnectionProperties.JdbcPassword.name(), "");
        // SchemaPool OFF so both roles bind their own role-resolved reader, but
        // the segment/value caches stay ON (we are testing cache isolation).
        p.put("UseSchemaPool", "false");
        p.put(RolapConnectionProperties.CatalogContent.name(), SCHEMA);
        if (role != null) {
            p.put(RolapConnectionProperties.Role.name(), role);
        }
        return DriverManager.getConnection(p, null, null);
    }

    private static long total(Connection conn) {
        Query q = conn.parseQuery(MDX);
        Result r = conn.execute(q);
        try {
            return ((Number) r.getCell(new int[]{0}).getValue()).longValue();
        } finally {
            r.close();
        }
    }

    /** crossRolePartialRollupCacheIsolation: full role first warms the cache
     *  (160), then partial role must STILL see 150 — never the cached 160. */
    @Test
    public void fullThenPartialNoBleed() {
        Connection full = connect("FullRole");
        Connection partial = connect("PartialRole");
        try {
            assertEquals(160L, total(full),
                "FULL rollup parent = true total over all children");
            assertEquals(150L, total(partial),
                "PARTIAL rollup must see only visible children (150), NEVER "
                + "the full role's cached 160");
        } finally {
            full.close();
            partial.close();
        }
    }

    /** Reverse order: partial warms (150), then full must STILL see 160. */
    @Test
    public void partialThenFullNoBleed() {
        Connection partial = connect("PartialRole");
        Connection full = connect("FullRole");
        try {
            assertEquals(150L, total(partial),
                "PARTIAL rollup parent = sum of visible children");
            assertEquals(160L, total(full),
                "FULL rollup must see the true 160, NEVER the partial role's "
                + "cached 150");
            // Re-read partial — still its own value (cache not corrupted).
            assertEquals(150L, total(partial),
                "partial re-read unchanged after full's read");
        } finally {
            partial.close();
            full.close();
        }
    }
}
