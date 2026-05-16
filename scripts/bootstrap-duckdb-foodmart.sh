#!/usr/bin/env bash
#
# Bootstraps a DuckDB FoodMart database for the cross-backend
# equivalence harness.
#
# Steps:
#   1. Run a fast test that triggers FoodMartHsqldbBootstrap.ensureExtracted(),
#      which extracts the mondrian-data-foodmart-hsql jar into target/foodmart/.
#   2. Build the test classpath via Maven.
#   3. Invoke MondrianFoodMartLoader to copy HSQLDB → DuckDB with
#      aggregate tables included.
#
# Output: ${DUCKDB_OUT:-/tmp/foodmart.duckdb}
#
# Used by .github/workflows/cross-backend-equivalence.yml and by
# developers running the cross-DB equivalence harness locally.

set -euo pipefail

DUCKDB_OUT="${DUCKDB_OUT:-/tmp/foodmart.duckdb}"
HSQLDB_URL="jdbc:hsqldb:file:target/foodmart/foodmart;shutdown=true"

echo "[bootstrap] target DuckDB: ${DUCKDB_OUT}"

# Wipe any prior DuckDB file so the loader's CREATE TABLE / INSERT runs
# against a clean fixture.
rm -f "${DUCKDB_OUT}"

echo "[bootstrap] building test classpath..."
mvn -B -ntp -q test-compile dependency:build-classpath \
    -Dmdep.outputFile=target/test-classpath.txt \
    -DincludeScope=test
TEST_CP="$(cat target/test-classpath.txt):target/classes:target/test-classes"

echo "[bootstrap] extracting HSQLDB FoodMart fixture..."
java -cp "${TEST_CP}" \
    mondrian.test.calcite.ExtractFoodMartHsqldb

if [[ ! -d target/foodmart ]]; then
  echo "[bootstrap] ERROR: target/foodmart/ not extracted." >&2
  exit 1
fi

echo "[bootstrap] copying HSQLDB → DuckDB via MondrianFoodMartLoader..."
java -cp "${TEST_CP}" \
    mondrian.test.loader.MondrianFoodMartLoader \
    -tables -data -indexes -aggregates \
    -jdbcDrivers=org.hsqldb.jdbcDriver,org.duckdb.DuckDBDriver \
    -inputJdbcURL="${HSQLDB_URL}" \
    -inputJdbcUser=sa \
    -outputJdbcURL="jdbc:duckdb:${DUCKDB_OUT}"

echo "[bootstrap] done. DuckDB FoodMart at ${DUCKDB_OUT}"
ls -lh "${DUCKDB_OUT}"
