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

import mondrian.rolap.RolapStar;
import mondrian.rolap.agg.Segment;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row-security fail-closed guard: when the caller's identity cannot be
 * determined, the secured-load detectors must report SECURED.
 *
 * <p>{@code isPredicateSecuredLoad} / {@code isBridgeMemberSecuredLoad} gate
 * every non-Calcite path that could serve fact rows without the row-security
 * filter (see SegmentLoader). They read the role off the current
 * {@link mondrian.server.Locus}. While {@code Locus.peek()} threw on an empty
 * stack those paths failed loudly by accident; making the lookup null-safe
 * would otherwise have turned "no Locus" into "role == null" — i.e. "not
 * secured" — and quietly allowed an unfiltered read.
 *
 * <p>This test runs with NO Locus pushed, which is the "identity unknown"
 * state, and asserts both detectors fail closed.
 */
public class RoleContextFailClosedTest {

    @Test public void detectorsFailClosedWithoutLocus() {
        final List<Segment> noSegments = Collections.emptyList();
        final RolapStar noStar = null;

        assertTrue(
            CalcitePlannerAdapters.isPredicateSecuredLoad(
                noSegments, noStar),
            "isPredicateSecuredLoad must report SECURED when there is no "
            + "Locus: the role is unknown, so no caller may assume the load "
            + "is unrestricted");

        assertTrue(
            CalcitePlannerAdapters.isBridgeMemberSecuredLoad(
                noSegments, noStar),
            "isBridgeMemberSecuredLoad must report SECURED when there is no "
            + "Locus, for the same reason");
    }
}

// End RoleContextFailClosedTest.java
