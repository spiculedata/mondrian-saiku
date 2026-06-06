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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #104: a raw-SQL proof, on H2, that the exact ordered-set aggregate
 * SQL the Calcite adapter emits for a {@code median} / {@code percentile}
 * measure —
 * <pre>PERCENTILE_CONT(p) WITHIN GROUP (ORDER BY value)</pre>
 * — computes the hand-computed value. HSQLDB 1.8 (the FoodMart fixture DB)
 * cannot run ordered-set aggregates, so these non-additive aggregators are
 * proven against H2 instead, mirroring {@link SymmetricAggregateSqlTest}'s
 * raw-SQL approach for the fan-out maths.
 *
 * <pre>
 *   region  amount
 *   North   10, 20, 30          median 20   p90 28
 *   South   5, 15, 25, 35       median 20   p90 32
 * </pre>
 */
public class PercentileSqlProofTest {

    private static Connection conn;

    @BeforeAll
    public static void boot() throws Exception {
        Class.forName("org.h2.Driver");
        conn = DriverManager.getConnection(
            "jdbc:h2:mem:pctile;DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE \"sales\" (\"region\" VARCHAR(8),"
                + " \"amount\" INTEGER)");
            st.execute(
                "INSERT INTO \"sales\" VALUES"
                + " ('North',10),('North',20),('North',30),"
                + " ('South',5),('South',15),('South',25),('South',35)");
        }
    }

    @AfterAll
    public static void close() throws Exception {
        if (conn != null) {
            conn.close();
            conn = null;
        }
    }

    private Map<String, Double> byRegion(String aggSql) throws Exception {
        Map<String, Double> out = new LinkedHashMap<>();
        String sql =
            "SELECT \"region\", " + aggSql + " AS \"v\""
            + " FROM \"sales\" GROUP BY \"region\" ORDER BY \"region\"";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql))
        {
            while (rs.next()) {
                Object v = rs.getObject(2);
                out.put(
                    rs.getString(1),
                    v == null ? null : ((Number) v).doubleValue());
            }
        }
        return out;
    }

    /** median = PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY amount). */
    @Test
    public void medianMatchesHandComputed() throws Exception {
        Map<String, Double> m = byRegion(
            "PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY \"amount\")");
        assertEquals(20.0, m.get("North"), 0.001);
        assertEquals(20.0, m.get("South"), 0.001);
    }

    /** percentile(90) = PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY amount). */
    @Test
    public void p90MatchesHandComputed() throws Exception {
        Map<String, Double> m = byRegion(
            "PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY \"amount\")");
        assertEquals(28.0, m.get("North"), 0.001);
        assertEquals(32.0, m.get("South"), 0.001);
    }
}
