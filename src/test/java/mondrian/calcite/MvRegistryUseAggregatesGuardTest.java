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
import mondrian.olap.MondrianProperties;
import mondrian.olap.Util;
import mondrian.rolap.RolapConnection;
import mondrian.rolap.RolapSchema;
import mondrian.test.FoodMartHsqldbBootstrap;
import mondrian.test.TestContext;

import org.junit.jupiter.api.AfterEach;import org.junit.jupiter.api.BeforeEach;import org.junit.jupiter.api.BeforeAll;import org.junit.jupiter.api.Test;
import javax.sql.DataSource;

import static org.junit.Assert.assertEquals;import static org.junit.Assert.assertNotNull;
/**
 * Regression guard for the {@link MvRegistry#fromSchema} contract that
 * {@code mondrian.rolap.aggregates.Use=false} disables MV-based
 * agg-table routing.
 *
 * <p>The bug: prior to the v4.8.1.8 fix, {@code MvRegistry.fromSchema}
 * registered materialised views regardless of the {@code UseAggregates}
 * property, causing segment-load requests to silently route to agg
 * tables even when the operator had administratively turned aggregates
 * off. On a partial / empty agg table that produces silently-wrong
 * cell values — a result-divergence bug invisible to anyone who isn't
 * cross-checking against the legacy SQL builder.
 *
 * <p>This test asserts the registry returns zero materialisations when
 * the property is off, then verifies the FoodMart fixture *does*
 * register MVs when the property is on, so the guard is necessary and
 * sufficient.
 */
public class MvRegistryUseAggregatesGuardTest {

    private boolean savedUseAggregates;

    @BeforeAll
    public static void bootFoodMart() {
        FoodMartHsqldbBootstrap.ensureExtracted();
    }

    @BeforeEach
    public void saveProperty() {
        savedUseAggregates =
            MondrianProperties.instance().UseAggregates.get();
    }

    @AfterEach
    public void restoreProperty() {
        MondrianProperties.instance().UseAggregates.set(savedUseAggregates);
    }

    @Test
    public void useAggregatesFalseProducesEmptyRegistry() {
        MondrianProperties.instance().UseAggregates.set(false);
        MvRegistry reg = buildRegistry();
        assertNotNull("registry must not be null", reg);
        assertEquals(
            "UseAggregates=false MUST produce an empty MV registry — "
                + "the SPI contract is that the property disables "
                + "agg-table routing across both the legacy findAgg "
                + "path AND the Calcite MV path. Got "
                + reg.materializations().size() + " materialisations.",
            0,
            reg.materializations().size());
    }

    @Test
    public void useAggregatesTrueProducesNonEmptyRegistry() {
        MondrianProperties.instance().UseAggregates.set(true);
        MvRegistry reg = buildRegistry();
        assertNotNull("registry must not be null", reg);
        // Sanity check: the guard above only matters if the fixture
        // actually has aggregates declared. FoodMart's shape catalog
        // currently emits >= 4 materialisations (see MvRegistryTest).
        // If this drops to 0 the FoodMart fixture has lost its
        // <MeasureGroup type='aggregate'> declarations and the
        // negative test above becomes meaningless.
        org.junit.Assert.assertTrue(
            "with UseAggregates=true FoodMart should register at "
                + "least one materialisation, got "
                + reg.materializations().size(),
            reg.materializations().size() >= 1);
    }

    private MvRegistry buildRegistry() {
        Util.PropertyList props =
            Util.parseConnectString(TestContext.getDefaultConnectString());
        props.put("UseSchemaPool", "false");
        Connection conn =
            DriverManager.getConnection(props, null, null);
        try {
            RolapConnection rc = (RolapConnection) conn;
            RolapSchema schema = rc.getSchema();
            DataSource ds = rc.getDataSource();
            CalciteMondrianSchema cms =
                new CalciteMondrianSchema(ds, "mondrian");
            return MvRegistry.fromSchema(schema, cms);
        } finally {
            conn.close();
        }
    }
}

// End MvRegistryUseAggregatesGuardTest.java
