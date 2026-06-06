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

import mondrian.lookml.json.ExploreJsonReader;
import mondrian.lookml.parse.FlattenResult;
import mondrian.lookml.parse.LookmlFlattener;
import mondrian.lookml.parse.LookmlNode;
import mondrian.lookml.parse.LookmlParseException;
import mondrian.lookml.parse.LookmlParser;
import mondrian.lookml.transpile.LookmlTranspiler;
import mondrian.lookml.transpile.TranspileResult;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.nio.file.FileVisitOption;

/**
 * Issue #102: the end-user CLI for the LookML import coverage report — the
 * de-risking artefact a migrating customer reads before trusting the
 * conversion. Mirrors {@link mondrian.schema.yaml.SchemaCli} in shape:
 * {@link #run(String[], PrintStream, PrintStream)} returns an exit code and
 * {@link #main} delegates then calls {@code System.exit}.
 *
 * <h3>Subcommand</h3>
 *
 * <pre>
 *   lookml-report report &lt;path&gt; [-o report.md] [--json report.json]
 *                                  [--fail-on-refuse]
 * </pre>
 *
 * <p>{@code <path>} is a single {@code .lkml} file or a directory of
 * {@code .lkml} files. For a directory every {@code .lkml} file is discovered
 * recursively ({@code Files.walk}) and parsed INDEPENDENTLY: parseable files
 * have their top-level objects concatenated into one document for
 * classification (LookML's top level is a flat list of {@code view:} /
 * {@code explore:} / {@code model:} blocks); a file that fails to parse is
 * collected under "Unparseable files" rather than aborting the whole batch; and
 * {@code *.dashboard.lkml} files (YAML-structured dashboards) are skipped and
 * listed under "Skipped files". The merged document is then run through the
 * {@link mondrian.lookml.parse.LookmlFlattener} (issue #116): {@code include:}
 * (already satisfied by the merge), {@code extends:}, refinements
 * ({@code +view}/{@code +explore}) and {@code @{}} constants are resolved so a
 * construct that only becomes additive/Liquid/secured after refinement is
 * classified on the RESOLVED model. A reference that cannot be resolved is
 * reported as a {@code flatten:} diagnostic on stderr and left as-parsed (never
 * silently dropped).
 *
 * <p>An alternative input, {@code --explore-json <file>}, reads a pre-resolved
 * Looker {@code LookmlModelExplore} JSON (issue #116, part B) via
 * {@link mondrian.lookml.json.ExploreJsonReader}; that path is already flattened
 * by Looker, so it skips the flatten pass.
 *
 * <p>By default the Markdown report is printed to stdout; {@code -o} writes it
 * to a file and {@code --json} additionally writes the machine-readable JSON.
 * {@code --fail-on-refuse} makes the command a CI gate: a non-empty refuse
 * bucket yields a non-zero exit code (the report is still emitted first).
 *
 * <h3>Exit codes</h3>
 *
 * <ul>
 *   <li>{@code 0} — success, including PARTIAL success in directory mode (some
 *       files unparseable but at least one parsed); the unparseable list is in
 *       the report</li>
 *   <li>{@code 1} — bad arguments / unknown subcommand</li>
 *   <li>{@code 2} — missing/unreadable path, a single-file parse failure,
 *       nothing parseable at all (empty dir / every file unparseable or
 *       skipped), or a refusal with {@code --fail-on-refuse}</li>
 * </ul>
 */
public final class LookmlReportCli {

  private static final String USAGE =
      "usage:\n"
      + "  lookml-report report <path> [-o report.md] [--json report.json]"
      + " [--fail-on-refuse]\n"
      + "  lookml-report report --explore-json <file> [-o report.md]"
      + " [--json report.json] [--fail-on-refuse]\n"
      + "\n"
      + "  <path> is a .lkml file or a directory of .lkml files. A directory is\n"
      + "  flattened (include/extends/refinements/@{} resolved) before report.\n"
      + "  --explore-json reads a pre-resolved Looker LookmlModelExplore JSON.\n";

  private static final String LKML_SUFFIX = ".lkml";
  private static final int RC_OK = 0;
  private static final int RC_BAD_ARGS = 1;
  private static final int RC_FAILURE = 2;

  private LookmlReportCli() {}

  /** Standalone JVM entry point; exits with the returned code. */
  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /**
   * Programmatic entry point — same behaviour as {@link #main} but returns the
   * exit code instead of calling {@code System.exit}, and takes injectable
   * streams. Used by the test harness.
   */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    if (args == null || args.length == 0) {
      err.print(USAGE);
      return RC_BAD_ARGS;
    }
    final String sub = args[0];
    if (!"report".equals(sub)) {
      err.println("error: unknown subcommand: " + sub);
      err.print(USAGE);
      return RC_BAD_ARGS;
    }
    return report(args, out, err);
  }

  private static int report(String[] args, PrintStream out, PrintStream err) {
    final Options opts = Options.parse(args);
    if (opts.exploreJson != null) {
      return reportExploreJson(opts, out, err);
    }
    if (opts.path == null) {
      err.println("error: report requires an input path");
      err.print(USAGE);
      return RC_BAD_ARGS;
    }

    final Ingest ingest;
    try {
      ingest = ingest(opts.path);
    } catch (java.nio.file.NoSuchFileException e) {
      err.println("error: cannot read " + opts.path + ": no such file");
      return RC_FAILURE;
    } catch (IOException | UncheckedIOException e) {
      err.println("error: cannot read " + opts.path + ": "
          + rootMessage(e));
      return RC_FAILURE;
    }

    // Nothing parseable at all -> hard failure (rc 2). For a directory this
    // means every .lkml file was unparseable or skipped.
    if (ingest.lookml.isBlank() && ingest.diagnostics.unparseable().isEmpty()
        && ingest.diagnostics.skipped().isEmpty()) {
      err.println("error: " + opts.path + " contains no " + LKML_SUFFIX
          + " files");
      return RC_FAILURE;
    }
    if (ingest.lookml.isBlank()) {
      err.println("error: nothing parseable in " + opts.path + " ("
          + ingest.diagnostics.unparseable().size() + " unparseable, "
          + ingest.diagnostics.skipped().size() + " skipped)");
      return RC_FAILURE;
    }

    final CoverageReport coverage;
    try {
      final LookmlNode parsed = LookmlParser.parse(ingest.lookml);
      // Multi-file resolution (issue #116, part A): resolve include/extends/
      // refinements/@{} so a construct that only becomes additive/Liquid/
      // secured after refinement is classified on the RESOLVED model. Flatten
      // diagnostics are printed to stderr (unresolved refs are never dropped).
      final FlattenResult flat = new LookmlFlattener().flatten(parsed);
      flat.diagnostics().forEach(d -> err.println("flatten: " + d));
      final TranspileResult tr =
          new LookmlTranspiler().transpile(flat.document());
      coverage = CoverageReport.from(tr);
    } catch (LookmlParseException e) {
      // Single-file mode (or a merged doc that still won't parse): graceful.
      err.println("error: failed to parse LookML at " + opts.path + ": "
          + rootMessage(e));
      return RC_FAILURE;
    } catch (RuntimeException e) {
      err.println("error: failed to build coverage report for " + opts.path
          + ": " + rootMessage(e));
      return RC_FAILURE;
    }

    final int emitRc = emit(coverage, ingest.diagnostics, opts, out, err);
    if (emitRc != RC_OK) {
      return emitRc;
    }

    if (opts.failOnRefuse && coverage.metrics().explore().refuse()
        + coverage.metrics().field().refuse() > 0) {
      err.println("error: refusals present and --fail-on-refuse was set");
      return RC_FAILURE;
    }
    return RC_OK;
  }

  /**
   * Report from a pre-resolved Looker {@code LookmlModelExplore} JSON file
   * (issue #116, part B). The JSON is already flattened by Looker, so the
   * flatten pass is not applied; the mapped AST goes straight to the
   * transpiler.
   */
  private static int reportExploreJson(Options opts, PrintStream out,
      PrintStream err) {
    final String json;
    try {
      json = Files.readString(opts.exploreJson, StandardCharsets.UTF_8);
    } catch (java.nio.file.NoSuchFileException e) {
      err.println("error: cannot read " + opts.exploreJson
          + ": no such file");
      return RC_FAILURE;
    } catch (IOException | UncheckedIOException e) {
      err.println("error: cannot read " + opts.exploreJson + ": "
          + rootMessage(e));
      return RC_FAILURE;
    }

    final CoverageReport coverage;
    try {
      final LookmlNode doc = new ExploreJsonReader().read(json);
      final TranspileResult tr = new LookmlTranspiler().transpile(doc);
      coverage = CoverageReport.from(tr);
    } catch (RuntimeException e) {
      err.println("error: failed to read LookmlModelExplore JSON at "
          + opts.exploreJson + ": " + rootMessage(e));
      return RC_FAILURE;
    }

    final int emitRc = emit(coverage, IngestDiagnostics.none(), opts, out, err);
    if (emitRc != RC_OK) {
      return emitRc;
    }
    if (opts.failOnRefuse && coverage.metrics().explore().refuse()
        + coverage.metrics().field().refuse() > 0) {
      err.println("error: refusals present and --fail-on-refuse was set");
      return RC_FAILURE;
    }
    return RC_OK;
  }

  /** Writes the Markdown (stdout or {@code -o}) and any {@code --json}. */
  private static int emit(CoverageReport coverage, IngestDiagnostics diag,
      Options opts, PrintStream out, PrintStream err) {
    final String markdown = new MarkdownReportWriter().write(coverage, diag);
    if (opts.markdownOut != null) {
      try {
        Files.writeString(opts.markdownOut, markdown, StandardCharsets.UTF_8);
      } catch (IOException e) {
        err.println("error: cannot write " + opts.markdownOut + ": "
            + rootMessage(e));
        return RC_FAILURE;
      }
    } else {
      out.print(markdown);
    }
    if (opts.jsonOut != null) {
      final String json = new JsonReportWriter().write(coverage, diag);
      try {
        Files.writeString(opts.jsonOut, json, StandardCharsets.UTF_8);
      } catch (IOException e) {
        err.println("error: cannot write " + opts.jsonOut + ": "
            + rootMessage(e));
        return RC_FAILURE;
      }
    }
    return RC_OK;
  }

  /** The merged LookML text plus per-file ingest diagnostics. */
  private static final class Ingest {
    private final String lookml;
    private final IngestDiagnostics diagnostics;

    private Ingest(String lookml, IngestDiagnostics diagnostics) {
      this.lookml = lookml;
      this.diagnostics = diagnostics;
    }
  }

  /**
   * Reads LookML from a single file, or from every {@code .lkml} file under a
   * directory (recursively, sorted for determinism). In directory mode each
   * file is parsed INDEPENDENTLY: parseable files are merged for
   * classification, unparseable files are collected as diagnostics (never a
   * hard abort), and {@code *.dashboard.lkml} files are skipped (issue #98).
   */
  private static Ingest ingest(Path path) throws IOException {
    if (!Files.isDirectory(path)) {
      return new Ingest(Files.readString(path, StandardCharsets.UTF_8),
          IngestDiagnostics.none());
    }
    return ingestDirectory(path);
  }

  private static Ingest ingestDirectory(Path dir) throws IOException {
    final List<Path> files;
    try (Stream<Path> walk =
        Files.walk(dir, FileVisitOption.FOLLOW_LINKS)) {
      files = walk
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
              .endsWith(LKML_SUFFIX))
          .sorted()
          .collect(Collectors.toList());
    }
    final StringBuilder merged = new StringBuilder();
    final List<IngestDiagnostics.Entry> unparseable = new ArrayList<>();
    final List<IngestDiagnostics.Entry> skipped = new ArrayList<>();
    for (Path f : files) {
      final String rel = dir.relativize(f).toString();
      if (isDashboardFile(f)) {
        skipped.add(new IngestDiagnostics.Entry(rel,
            "dashboard LKML (YAML-structured) is not view/model/explore"));
        continue;
      }
      final String text = Files.readString(f, StandardCharsets.UTF_8);
      try {
        // Parse independently so one bad file cannot abort the batch.
        LookmlParser.parse(text);
        merged.append(text).append('\n');
      } catch (RuntimeException e) {
        // LookmlParseException (a RuntimeException) and any other parse-time
        // failure are collected, never propagated — one bad file must not
        // abort the batch.
        unparseable.add(new IngestDiagnostics.Entry(rel, rootMessage(e)));
      }
    }
    return new Ingest(merged.toString(),
        IngestDiagnostics.of(unparseable, skipped));
  }

  /** True for {@code *.dashboard.lkml} (YAML-structured dashboards, leading
   * {@code -}); these are not view/model/explore LookML and are skipped. */
  private static boolean isDashboardFile(Path f) {
    return f.getFileName().toString().toLowerCase(Locale.ROOT)
        .endsWith(".dashboard" + LKML_SUFFIX);
  }

  private static String rootMessage(Throwable t) {
    Throwable cur = t;
    while (cur.getCause() != null && cur.getCause() != cur) {
      cur = cur.getCause();
    }
    final String msg = cur.getMessage();
    return msg == null ? cur.getClass().getSimpleName() : msg;
  }

  /** Parsed CLI options for the {@code report} subcommand. */
  private static final class Options {
    private final Path path;
    private final Path exploreJson;
    private final Path markdownOut;
    private final Path jsonOut;
    private final boolean failOnRefuse;

    private Options(Path path, Path exploreJson, Path markdownOut, Path jsonOut,
        boolean failOnRefuse) {
      this.path = path;
      this.exploreJson = exploreJson;
      this.markdownOut = markdownOut;
      this.jsonOut = jsonOut;
      this.failOnRefuse = failOnRefuse;
    }

    private static Options parse(String[] args) {
      Path path = null;
      Path exploreJson = null;
      Path markdownOut = null;
      Path jsonOut = null;
      boolean failOnRefuse = false;
      final List<String> positional = new ArrayList<>();
      for (int i = 1; i < args.length; i++) {
        final String a = args[i];
        switch (a) {
        case "-o":
          if (i + 1 < args.length) {
            markdownOut = Paths.get(args[++i]);
          }
          break;
        case "--json":
          if (i + 1 < args.length) {
            jsonOut = Paths.get(args[++i]);
          }
          break;
        case "--explore-json":
          if (i + 1 < args.length) {
            exploreJson = Paths.get(args[++i]);
          }
          break;
        case "--fail-on-refuse":
          failOnRefuse = true;
          break;
        default:
          positional.add(a);
          break;
        }
      }
      if (!positional.isEmpty()) {
        path = Paths.get(positional.get(0));
      }
      return new Options(path, exploreJson, markdownOut, jsonOut, failOnRefuse);
    }
  }
}

// End LookmlReportCli.java
