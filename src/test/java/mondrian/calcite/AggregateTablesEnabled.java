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

import mondrian.olap.MondrianProperties;
import mondrian.test.PropertySaver;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Turns Mondrian's aggregate-table properties on for the duration of a test
 * class, then restores the previous values.
 *
 * <p>Both {@code mondrian.rolap.aggregates.Use} and
 * {@code mondrian.rolap.aggregates.Read} default to {@code false}, and
 * {@link MvRegistry#fromSchema} honours that by returning an <em>empty</em>
 * registry — deliberately, so the Calcite MV rule cannot route queries to
 * agg tables behind an operator who switched them off. Any test that asserts
 * a rewrite therefore has to enable them first, or it is asserting against a
 * registry that was never populated.
 *
 * <p>Registered with {@code @ExtendWith(AggregateTablesEnabled.class)}. The
 * callback runs before the test class's own {@code @BeforeAll}, so a fixture
 * that builds an {@link MvRegistry} there sees the properties already set.
 *
 * <p>The properties are process-global, so restoring them matters: the suite
 * reuses one JVM across classes and leaking {@code Use=true} would silently
 * change agg-table routing for every later test.
 */
public class AggregateTablesEnabled
    implements BeforeAllCallback, AfterAllCallback
{
    private final PropertySaver saver = new PropertySaver();

    @Override public void beforeAll(ExtensionContext context) {
        final MondrianProperties props = MondrianProperties.instance();
        saver.set(props.UseAggregates, true);
        saver.set(props.ReadAggregates, true);
    }

    @Override public void afterAll(ExtensionContext context) {
        saver.reset();
    }
}

// End AggregateTablesEnabled.java
