/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.test.calcite;

import mondrian.olap.MondrianProperties;
import mondrian.rolap.agg.SegmentLoader;
import mondrian.test.FoodMartHsqldbBootstrap;
import mondrian.test.calcite.corpus.SmokeCorpus.NamedMdx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A native filter that EXCLUDES members ({@code Not In}, {@code Except})
 * must not be translated as if it selected them.
 *
 * <p>{@code MemberListCrossJoinArg} carries an {@code exclude} flag, and the
 * Calcite tuple read used to emit its member list as a plain IN-list while
 * ignoring that flag — so a query asking for "customers NOT in CA" was served
 * SQL reading {@code WHERE state_province = 'CA'} and returned exactly the
 * rows it had asked to leave out. No error, just the complement of the right
 * answer. Legacy negates the predicate (with the NULL handling a negated
 * multi-column key needs), so these queries fall back to it.
 *
 * <p>Every case asserts the two backends agree and that the result is
 * non-empty, so the test cannot pass by both returning nothing.
 */
public class ExcludedMemberFilterTest {

    @BeforeAll public static void bootFoodMart() {
        FoodMartHsqldbBootstrap.ensureExtracted();
    }

    @AfterEach public void clearBackend() {
        System.clearProperty("mondrian.backend");
        SegmentLoader.clearCalcitePlannerCache();
    }

    /** The reported shape: Not In on an ancestor, crossjoined. */
    @Test public void notInFilterAgreesAcrossBackends() {
        assertBackendsAgree(
            "With "
            + "Set [*NATIVE_CJ_SET] as 'NonEmptyCrossJoin("
            + "[*BASE_MEMBERS_Customers],[*BASE_MEMBERS_Product])' "
            + "Set [*BASE_MEMBERS_Customers] as "
            + "'Filter([Customers].[City].Members,"
            + "Ancestor([Customers].CurrentMember,"
            + " [Customers].[State Province])"
            + " Not In {[Customers].[All Customers].[USA].[CA]})' "
            + "Set [*BASE_MEMBERS_Product] as "
            + "'Filter([Product].[Product Family].Members,"
            + "[Product].CurrentMember"
            + " Not In {[Product].[All Products].[Drink]})' "
            + "Select {[Measures].[Customer Count]} on columns, "
            + "Non Empty [*NATIVE_CJ_SET] on rows From [Sales]");
    }

    /** Single-column key exclusion. */
    @Test public void notInOnSingleKeyAgreesAcrossBackends() {
        assertBackendsAgree(
            "Select {[Measures].[Unit Sales]} on columns, "
            + "Non Empty Filter([Product].[Product Family].Members,"
            + " [Product].CurrentMember"
            + " Not In {[Product].[All Products].[Drink]}) on rows "
            + "From [Sales]");
    }

    /** Except() is the other spelling of the same exclusion. */
    @Test public void exceptAgreesAcrossBackends() {
        assertBackendsAgree(
            "Select {[Measures].[Unit Sales]} on columns, "
            + "Non Empty Except([Store].[Stores].[Store State].Members,"
            + " {[Store].[Stores].[USA].[CA]}) on rows "
            + "From [Sales]");
    }

    /** Control: the non-excluding form was always translated correctly. */
    @Test public void inFilterAgreesAcrossBackends() {
        assertBackendsAgree(
            "Select {[Measures].[Unit Sales]} on columns, "
            + "Non Empty Filter([Product].[Product Family].Members,"
            + " [Product].CurrentMember"
            + " In {[Product].[All Products].[Drink]}) on rows "
            + "From [Sales]");
    }

    private static void assertBackendsAgree(String mdx) {
        MondrianProperties.instance().ExpandNonNative.set(false);
        MondrianProperties.instance().EnableNativeFilter.set(true);
        final String legacy = runOn("legacy", mdx);
        final String calcite = runOn("calcite", mdx);
        assertEquals(
            "calcite must not return the complement of an excluded-member "
            + "filter:\n" + mdx, legacy, calcite);
        assertTrue(
            "expected rows, so an all-empty agreement cannot pass:\n"
            + calcite,
            calcite.contains("Row #0:"));
    }

    private static String runOn(String backend, String mdx) {
        System.setProperty("mondrian.backend", backend);
        SegmentLoader.clearCalcitePlannerCache();
        return FoodMartCapture.executeCold(
            new NamedMdx("excluded-member-filter", mdx), null).cellSet;
    }
}

// End ExcludedMemberFilterTest.java
