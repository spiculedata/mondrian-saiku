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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three fallback policies, and in particular that {@code full} forbids
 * serving a Calcite-backed read from the legacy SQL generator.
 *
 * <p>Deployments on a dialect the legacy generator does not support need
 * that: there, a "fallback" is not a graceful degradation, it is a failure —
 * and one that reports itself as a dialect error rather than as the
 * translator coverage gap it actually is.
 */
public class CalciteFallbackPolicyTest {

    @AfterEach public void clearProperty() {
        System.clearProperty(CalciteFallbackPolicy.STRICT_PROPERTY);
    }

    @Test public void defaultFallsBackOnGapsButNotOnUnknownFailures() {
        System.clearProperty(CalciteFallbackPolicy.STRICT_PROPERTY);
        assertTrue(
            CalciteFallbackPolicy.unsupportedShapeMayFallBack(),
            "by default a known translator gap still falls back");
        assertFalse(
            CalciteFallbackPolicy.unknownFailureMayFallBack(),
            "by default an unknown Calcite failure is fatal");
    }

    @Test public void fullForbidsEveryFallback() {
        System.setProperty(CalciteFallbackPolicy.STRICT_PROPERTY, "full");
        assertFalse(
            CalciteFallbackPolicy.unsupportedShapeMayFallBack(),
            "strict=full must not serve an unsupported shape from legacy");
        assertFalse(
            CalciteFallbackPolicy.unknownFailureMayFallBack(),
            "strict=full must not serve an unknown failure from legacy");
    }

    @Test public void falseAllowsEveryFallback() {
        System.setProperty(CalciteFallbackPolicy.STRICT_PROPERTY, "false");
        assertTrue(
            CalciteFallbackPolicy.unsupportedShapeMayFallBack(),
            "strict=false keeps the old permissive behaviour");
        assertTrue(
            CalciteFallbackPolicy.unknownFailureMayFallBack(),
            "strict=false keeps the old permissive behaviour");
    }

    @Test public void valueIsCaseAndWhitespaceInsensitive() {
        System.setProperty(CalciteFallbackPolicy.STRICT_PROPERTY, "  FULL ");
        assertFalse(
            CalciteFallbackPolicy.unsupportedShapeMayFallBack(),
            "a stray case/whitespace difference must not silently re-enable "
            + "fallbacks");
    }

    @Test public void noFallbackErrorNamesSiteAndCause() {
        final UnsupportedTranslation cause =
            new UnsupportedTranslation("fromTupleRead: widget not modelled");
        final RuntimeException ex =
            CalciteFallbackPolicy.noFallback("tuple-read", cause);
        assertTrue(
            ex.getMessage().contains("tuple-read"),
            "message must name the site: " + ex.getMessage());
        assertTrue(
            ex.getMessage().contains("widget not modelled"),
            "message must carry the translator's reason: " + ex.getMessage());
        assertEquals(
            cause, ex.getCause(), "the refusal must be the cause");
    }
}

// End CalciteFallbackPolicyTest.java
