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
}

// End LookmlReportCliTest.java
