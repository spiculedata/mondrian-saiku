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

import mondrian.test.FoodMartHsqldbBootstrap;

/**
 * One-shot launcher that unpacks the HSQLDB FoodMart fixture into
 * {@code target/foodmart/} via {@link FoodMartHsqldbBootstrap#ensureExtracted()}.
 *
 * <p>Used by {@code scripts/bootstrap-duckdb-foodmart.sh} (and the
 * matching GitHub Actions job) to materialise the HSQLDB fixture before
 * running {@code MondrianFoodMartLoader} to copy it into DuckDB.
 *
 * <p>Pure infrastructure — no assertions, no test framework. Exists
 * because {@code FoodMartHsqldbBootstrap.ensureExtracted()} is a static
 * helper without a {@code main} method; Java has no inline-script flag,
 * so we ship the smallest possible launcher.
 */
public final class ExtractFoodMartHsqldb {

    private ExtractFoodMartHsqldb() {}

    public static void main(String[] args) {
        FoodMartHsqldbBootstrap.ensureExtracted();
        System.out.println(
            "[ExtractFoodMartHsqldb] target/foodmart/ extracted.");
    }
}

// End ExtractFoodMartHsqldb.java
