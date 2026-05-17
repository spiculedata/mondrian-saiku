/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule / Saiku community
// All Rights Reserved.
*/
package mondrian.test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.hsqldb.jdbc.jdbcDataSource;

/**
 * Extracts the pre-loaded HSQLDB 1.8 FoodMart database files shipped inside
 * the {@code mondrian-data-foodmart-hsql} test-scope artifact onto the local
 * filesystem under {@code target/foodmart/} so that tests using a
 * {@code jdbc:hsqldb:file:target/foodmart/foodmart} URL can open the DB.
 *
 * <p>Idempotent and thread-safe: the extraction runs at most once per JVM.
 *
 * <p>Also exposes the canonical JDBC URL ({@link #JDBC_URL}) and a
 * convenience {@link #dataSource()} factory so every Calcite test opens
 * the fixture with identical connection parameters. HSQLDB 1.8 file-mode
 * binds the database's read/write mode to whichever connection opens it
 * first in the JVM; mixing {@code ;readonly=true} with the
 * {@code mondrian.properties} {@code ;shutdown=true} URL across test
 * classes used to make subsequent tests fail in DBCP's connection-pool
 * activation with "The database is in read only mode". Sharing this
 * constant keeps every test on the same URL the Mondrian connection uses,
 * so the file-lock mode never changes mid-run.
 *
 * <p>Holds a long-lived "pinning" {@link Connection} once the fixture is
 * extracted. {@code ;shutdown=true} in the URL tells HSQLDB to close the
 * database when the last connection releases — without the pin, every test
 * that opens and closes its own DataSource would force HSQLDB to re-replay
 * the FoodMart script (tens of seconds of row-by-row index build per test).
 * The pin keeps the in-JVM Database instance alive so subsequent connections
 * skip the reload; the JVM exit closes the pin cleanly.
 */
public final class FoodMartHsqldbBootstrap {

    private static final String[] ENTRIES = {
        "foodmart/foodmart.properties",
        "foodmart/foodmart.script"
    };

    private static final Path TARGET_DIR = Paths.get("target", "foodmart");

    /**
     * Canonical JDBC URL for the extracted FoodMart HSQLDB fixture. Matches
     * the {@code mondrian.foodmart.jdbcURL} value in {@code mondrian.properties}
     * so every test class opens the database in the same mode regardless of
     * run order.
     */
    public static final String JDBC_URL =
        "jdbc:hsqldb:file:target/foodmart/foodmart;shutdown=true";

    private static volatile boolean extracted = false;

    /** Long-lived pin that prevents {@code ;shutdown=true} from tearing down
     *  the HSQLDB Database between tests. See class-level Javadoc. */
    @SuppressWarnings("unused")
    private static Connection pinningConnection;

    private FoodMartHsqldbBootstrap() {
    }

    /**
     * @return a fresh {@link DataSource} pointing at the canonical FoodMart
     *     HSQLDB fixture. Calls {@link #ensureExtracted()} first so callers
     *     don't have to.
     */
    public static DataSource dataSource() {
        ensureExtracted();
        jdbcDataSource ds = new jdbcDataSource();
        ds.setDatabase(JDBC_URL);
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    /**
     * Ensure the HSQLDB files exist under {@code target/foodmart/}. Safe to
     * call from every test's static initializer — does real work only once.
     */
    public static synchronized void ensureExtracted() {
        if (extracted) {
            return;
        }
        Path marker = TARGET_DIR.resolve("foodmart.properties");
        try {
            if (!Files.exists(marker)) {
                Files.createDirectories(TARGET_DIR);
                ClassLoader cl = FoodMartHsqldbBootstrap.class.getClassLoader();
                for (String entry : ENTRIES) {
                    URL url = cl.getResource(entry);
                    if (url == null) {
                        throw new IOException(
                            "Could not locate " + entry + " on classpath. "
                            + "Is mondrian-data-foodmart-hsql:0.1 declared as a "
                            + "test dependency?");
                    }
                    // Strip the leading "foodmart/" — files land directly in target/foodmart/
                    String filename = entry.substring("foodmart/".length());
                    Path out = TARGET_DIR.resolve(filename);
                    try (InputStream in = url.openStream()) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                System.out.println(
                    "[FoodMartHsqldbBootstrap] extracted HSQLDB FoodMart fixture to "
                    + TARGET_DIR.toAbsolutePath());
            }
            extracted = true;
            pinDatabase();
        } catch (IOException e) {
            throw new RuntimeException(
                "Failed to extract HSQLDB FoodMart fixture to " + TARGET_DIR, e);
        }
    }

    /**
     * Open and hold a long-lived connection to the FoodMart database so the
     * in-JVM HSQLDB instance survives across tests. Without this pin, the
     * {@code ;shutdown=true} URL flag would shut HSQLDB down each time the
     * last test connection released, forcing the next test to replay the
     * full FoodMart script (millions of row inserts + index builds).
     *
     * <p>Idempotent — called from {@link #ensureExtracted()} under the same
     * lock. JVM exit closes the pin cleanly.
     */
    private static void pinDatabase() {
        if (pinningConnection != null) {
            return;
        }
        try {
            jdbcDataSource ds = new jdbcDataSource();
            ds.setDatabase(JDBC_URL);
            ds.setUser("sa");
            ds.setPassword("");
            pinningConnection = ds.getConnection();
        } catch (SQLException e) {
            throw new RuntimeException(
                "Failed to pin FoodMart HSQLDB instance via " + JDBC_URL, e);
        }
    }
}
