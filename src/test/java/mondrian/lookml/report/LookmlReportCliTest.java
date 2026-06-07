/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.lookml.report;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Issue #102: the end-user CLI for the coverage report. Mirrors
 * {@code mondrian.schema.yaml.SchemaCliTest}: {@code run(argv, out, err)} is
 * invoked in-process with captured streams.
 *
 * <p>Exit codes: 0 success, 1 bad args / unknown subcommand,
 * 2 missing/unreadable path or parse failure. {@code --fail-on-refuse} returns
 * non-zero when the refuse bucket is non-empty.
 */
public class LookmlReportCliTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private ByteArrayOutputStream out;
  private ByteArrayOutputStream err;
  private PrintStream outP;
  private PrintStream errP;

  /** A clean single-base star: one explore, one measure, one dimension. */
  private static final String CLEAN_LOOKML =
      "view: orders {\n"
      + "  sql_table_name: orders ;;\n"
      + "  dimension: status { type: string sql: ${TABLE}.status ;; }\n"
      + "  measure: top_amount { type: max sql: ${TABLE}.amount ;; }\n"
      + "}\n"
      + "view: users {\n"
      + "  sql_table_name: users ;;\n"
      + "  dimension: country { type: string sql: ${TABLE}.country ;; }\n"
      + "}\n"
      + "explore: orders {\n"
      + "  join: users { type: left_outer relationship: many_to_one\n"
      + "    sql_on: ${orders.user_id} = ${users.user_id} ;; }\n"
      + "}\n";

  /** Same star but with a sum fanned out one_to_many → a refusal. */
  private static final String REFUSE_LOOKML =
      "view: orders {\n"
      + "  sql_table_name: orders ;;\n"
      + "  dimension: status { type: string sql: ${TABLE}.status ;; }\n"
      + "  measure: fanned_sum { type: sum sql: ${TABLE}.amount ;; }\n"
      + "}\n"
      + "view: items {\n"
      + "  sql_table_name: users ;;\n"
      + "  dimension: country { type: string sql: ${TABLE}.country ;; }\n"
      + "}\n"
      + "explore: orders {\n"
      + "  join: items { type: left_outer relationship: one_to_many\n"
      + "    sql_on: ${orders.order_id} = ${items.user_id} ;; }\n"
      + "}\n";

  @Before
  public void setUp() {
    out = new ByteArrayOutputStream();
    err = new ByteArrayOutputStream();
    outP = new PrintStream(out, true, StandardCharsets.UTF_8);
    errP = new PrintStream(err, true, StandardCharsets.UTF_8);
  }

  @After
  public void tearDown() {
    outP.close();
    errP.close();
  }

  private String stdout() {
    return out.toString(StandardCharsets.UTF_8);
  }

  private String stderr() {
    return err.toString(StandardCharsets.UTF_8);
  }

  private Path write(String name, String content) throws Exception {
    Path f = tmp.newFile(name).toPath();
    Files.writeString(f, content, StandardCharsets.UTF_8);
    return f;
  }

  @Test
  public void noArgsPrintsUsageAndExitsNonZero() {
    int rc = LookmlReportCli.run(new String[0], outP, errP);
    assertNotEquals(0, rc);
    assertTrue("usage missing:\n" + stderr(),
        stderr().toLowerCase().contains("usage"));
  }

  @Test
  public void cleanModelPrintsMarkdownToStdoutWithRcZero() throws Exception {
    Path f = write("clean.lkml", CLEAN_LOOKML);
    int rc = LookmlReportCli.run(
        new String[] { "report", f.toString() }, outP, errP);
    assertEquals("stderr=" + stderr(), 0, rc);
    assertTrue("markdown title on stdout:\n" + stdout(),
        stdout().contains("# LookML Import Coverage Report"));
    assertTrue("summary section:\n" + stdout(), stdout().contains("## Summary"));
  }

  @Test
  public void writesMarkdownToOutputFileWhenDashO() throws Exception {
    Path f = write("clean.lkml", CLEAN_LOOKML);
    Path md = tmp.getRoot().toPath().resolve("report.md");
    int rc = LookmlReportCli.run(
        new String[] { "report", f.toString(), "-o", md.toString() },
        outP, errP);
    assertEquals("stderr=" + stderr(), 0, rc);
    String written = Files.readString(md, StandardCharsets.UTF_8);
    assertTrue("file has report:\n" + written,
        written.contains("# LookML Import Coverage Report"));
  }

  @Test
  public void writesJsonWhenDashDashJson() throws Exception {
    Path f = write("clean.lkml", CLEAN_LOOKML);
    Path json = tmp.getRoot().toPath().resolve("report.json");
    int rc = LookmlReportCli.run(
        new String[] { "report", f.toString(), "--json", json.toString() },
        outP, errP);
    assertEquals("stderr=" + stderr(), 0, rc);
    String written = Files.readString(json, StandardCharsets.UTF_8);
    assertTrue("json has metrics:\n" + written, written.contains("\"metrics\""));
    assertTrue("json has records:\n" + written, written.contains("\"records\""));
  }

  @Test
  public void failOnRefuseReturnsNonZeroWhenRefusalPresent() throws Exception {
    Path f = write("refuse.lkml", REFUSE_LOOKML);
    int rc = LookmlReportCli.run(
        new String[] { "report", f.toString(), "--fail-on-refuse" },
        outP, errP);
    assertNotEquals("expected non-zero rc when refuse bucket non-empty", 0, rc);
    // The report is still emitted so the user can see what was refused.
    assertTrue("report still printed:\n" + stdout(),
        stdout().contains("## Refuse"));
  }

  @Test
  public void failOnRefuseReturnsZeroWhenNoRefusals() throws Exception {
    Path f = write("clean.lkml", CLEAN_LOOKML);
    int rc = LookmlReportCli.run(
        new String[] { "report", f.toString(), "--fail-on-refuse" },
        outP, errP);
    assertEquals("clean model with --fail-on-refuse should pass:\n" + stderr(),
        0, rc);
  }

  @Test
  public void directoryOfLkmlFilesIsMergedAndClassified() throws Exception {
    Path dir = tmp.newFolder("project").toPath();
    Files.writeString(dir.resolve("views.lkml"),
        "view: orders {\n"
        + "  sql_table_name: orders ;;\n"
        + "  measure: top_amount { type: max sql: ${TABLE}.amount ;; }\n"
        + "}\n",
        StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("explores.lkml"),
        "explore: orders { }\n", StandardCharsets.UTF_8);

    int rc = LookmlReportCli.run(
        new String[] { "report", dir.toString() }, outP, errP);
    assertEquals("stderr=" + stderr(), 0, rc);
    assertTrue("merged doc produced a clean explore:\n" + stdout(),
        stdout().contains("orders"));
  }

  /** Issue #98 directory resilience: a directory with a good file, a nested
   * good file, a deliberately broken file, and a {@code .dashboard.lkml} must
   * (a) find the NESTED file's fields, (b) list the broken file under
   * "Unparseable files", (c) skip the dashboard (listed under "Skipped
   * files"), and (d) return rc 0 (partial success is not a failure). */
  @Test
  public void directoryResilienceMergesGoodFilesAndListsTheRest()
      throws Exception {
    Path dir = tmp.newFolder("project2").toPath();
    // A good top-level file.
    Files.writeString(dir.resolve("explores.lkml"),
        "explore: orders { }\n", StandardCharsets.UTF_8);
    // A good file in a NESTED subdir whose fields must be discovered.
    Path sub = Files.createDirectories(dir.resolve("views/sub"));
    Files.writeString(sub.resolve("nested.view.lkml"),
        "view: nested_orders {\n"
        + "  sql_table_name: orders ;;\n"
        + "  measure: nested_total { type: max sql: ${TABLE}.amount ;; }\n"
        + "}\n",
        StandardCharsets.UTF_8);
    // A deliberately broken file.
    Files.writeString(dir.resolve("broken.view.lkml"),
        "view: broken { this is not valid lookml {{{\n",
        StandardCharsets.UTF_8);
    // A dashboard file that must be skipped, not parsed.
    Files.writeString(dir.resolve("board.dashboard.lkml"),
        "- dashboard: sales\n  title: Sales\n", StandardCharsets.UTF_8);

    Path md = tmp.getRoot().toPath().resolve("r.md");
    Path json = tmp.getRoot().toPath().resolve("r.json");
    int rc = LookmlReportCli.run(
        new String[] { "report", dir.toString(),
            "-o", md.toString(), "--json", json.toString() },
        outP, errP);

    assertEquals("partial success must be rc 0; stderr=" + stderr(), 0, rc);
    String mdText = Files.readString(md, StandardCharsets.UTF_8);
    String jsonText = Files.readString(json, StandardCharsets.UTF_8);

    // (a) nested file's field is discovered.
    assertTrue("nested measure must be classified:\n" + mdText,
        mdText.contains("nested_total"));
    // (b) broken file is listed under Unparseable files (md + json).
    assertTrue("unparseable section present:\n" + mdText,
        mdText.contains("## Unparseable files"));
    assertTrue("broken file listed:\n" + mdText,
        mdText.contains("broken.view.lkml"));
    assertTrue("json unparseable list:\n" + jsonText,
        jsonText.contains("\"unparseableFiles\"")
            && jsonText.contains("broken.view.lkml"));
    // (c) dashboard skipped, not unparseable.
    assertTrue("skipped section present:\n" + mdText,
        mdText.contains("## Skipped files"));
    assertTrue("dashboard listed as skipped:\n" + mdText,
        mdText.contains("board.dashboard.lkml"));
    assertTrue("json skipped list:\n" + jsonText,
        jsonText.contains("\"skippedFiles\"")
            && jsonText.contains("board.dashboard.lkml"));
    // The dashboard must NOT appear in the unparseable list.
    int unparseAt = jsonText.indexOf("\"unparseableFiles\"");
    int skipAt = jsonText.indexOf("\"skippedFiles\"");
    assertTrue("dashboard must not be in the unparseable list",
        jsonText.substring(unparseAt, skipAt).indexOf("board.dashboard")
            < 0);
  }

  /** A directory in which every {@code .lkml} file is unparseable (and none
   * skipped-only) yields rc 2 — nothing classifiable. */
  @Test
  public void directoryWithOnlyUnparseableFilesReturnsRcTwo()
      throws Exception {
    Path dir = tmp.newFolder("project3").toPath();
    Files.writeString(dir.resolve("a.view.lkml"),
        "view: a { not valid {{{\n", StandardCharsets.UTF_8);
    int rc = LookmlReportCli.run(
        new String[] { "report", dir.toString() }, outP, errP);
    assertEquals("no parseable content -> rc 2; stderr=" + stderr(), 2, rc);
  }

  @Test
  public void unknownSubcommandPrintsUsageRcOne() {
    int rc = LookmlReportCli.run(new String[] { "bogus" }, outP, errP);
    assertEquals(1, rc);
    assertTrue("usage:\n" + stderr(), stderr().toLowerCase().contains("usage"));
  }

  @Test
  public void missingPathReturnsRcTwo() {
    int rc = LookmlReportCli.run(
        new String[] { "report", "/no/such/path.lkml" }, outP, errP);
    assertEquals(2, rc);
    assertTrue("error diagnostic:\n" + stderr(), !stderr().isBlank());
  }

  @Test
  public void unparseableLookmlReturnsRcTwoNotStackTrace() throws Exception {
    Path f = write("bad.lkml", "view: orders { this is not valid lookml {{{\n");
    int rc = LookmlReportCli.run(
        new String[] { "report", f.toString() }, outP, errP);
    assertEquals(2, rc);
    assertTrue("graceful error, no stack trace:\n" + stderr(),
        !stderr().isBlank() && !stderr().contains("\tat "));
  }

  // --- #116 part A: directory is flattened before classification ----------

  /** A two-file project where one file refines the measure to {@code type:
   * sum} on a fanned-out view must be FLATTENED before classification, so the
   * measure surfaces in the Refuse bucket (it would be CLEAN as-parsed). */
  @Test
  public void directoryIsFlattenedSoRefinementFlipsClassification()
      throws Exception {
    Path dir = tmp.newFolder("flatproj").toPath();
    Files.writeString(dir.resolve("orders.view.lkml"),
        "explore: orders {\n"
        + "  join: items { type: left_outer relationship: one_to_many\n"
        + "    sql_on: ${orders.id} = ${items.order_id} ;; }\n"
        + "}\n"
        + "view: orders { measure: revenue { sql: ${TABLE}.amount ;; } }\n"
        + "view: items { dimension: order_id { type: number } }\n",
        StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("orders_refine.view.lkml"),
        "view: +orders { measure: revenue { type: sum } }\n",
        StandardCharsets.UTF_8);

    int rc = LookmlReportCli.run(
        new String[] { "report", dir.toString(), "--fail-on-refuse" },
        outP, errP);
    assertNotEquals("refinement makes revenue a fanned-out sum -> refuse",
        0, rc);
    assertTrue("revenue refused after flatten:\n" + stdout(),
        stdout().contains("revenue") && stdout().contains("## Refuse"));
  }

  // --- #116 part B: --explore-json front-end ------------------------------

  /** {@code report --explore-json <file>} reports on a pre-resolved Looker
   * LookmlModelExplore JSON. */
  @Test
  public void exploreJsonInputProducesReport() throws Exception {
    String json =
        "{\n"
        + "  \"name\": \"orders\", \"view_name\": \"orders\",\n"
        + "  \"fields\": { \"dimensions\": [], \"measures\": [\n"
        + "    { \"name\": \"orders.revenue\", \"view\": \"orders\",\n"
        + "      \"type\": \"sum\", \"sql\": \"${TABLE}.amount\" } ] }\n"
        + "}\n";
    Path f = write("explore.json", json);
    int rc = LookmlReportCli.run(
        new String[] { "report", "--explore-json", f.toString() },
        outP, errP);
    assertEquals("stderr=" + stderr(), 0, rc);
    assertTrue("report title:\n" + stdout(),
        stdout().contains("# LookML Import Coverage Report"));
    assertTrue("revenue measure classified:\n" + stdout(),
        stdout().contains("revenue"));
  }

  /** A missing explore-json file is a graceful rc 2, not a stack trace. */
  @Test
  public void missingExploreJsonReturnsRcTwo() {
    int rc = LookmlReportCli.run(
        new String[] { "report", "--explore-json", "/no/such/explore.json" },
        outP, errP);
    assertEquals(2, rc);
    assertTrue("graceful error:\n" + stderr(),
        !stderr().isBlank() && !stderr().contains("\tat "));
  }
}

// End LookmlReportCliTest.java
