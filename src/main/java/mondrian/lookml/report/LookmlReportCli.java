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
 * {@code .lkml} files. For a directory every {@code .lkml} file is read and
 * their top-level objects are concatenated into one document for classification
 * (LookML's top level is a flat list of {@code view:} / {@code explore:} /
 * {@code model:} blocks). {@code include:} / {@code extends} / {@code @{}}
 * cross-file resolution is a documented v1 limitation (consistent with #100):
 * unresolved references are classified on their literal text.
 *
 * <p>By default the Markdown report is printed to stdout; {@code -o} writes it
 * to a file and {@code --json} additionally writes the machine-readable JSON.
 * {@code --fail-on-refuse} makes the command a CI gate: a non-empty refuse
 * bucket yields a non-zero exit code (the report is still emitted first).
 *
 * <h3>Exit codes</h3>
 *
 * <ul>
 *   <li>{@code 0} — success (no refusals, or {@code --fail-on-refuse} absent)
 *       </li>
 *   <li>{@code 1} — bad arguments / unknown subcommand</li>
 *   <li>{@code 2} — missing/unreadable path or LookML parse failure, or a
 *       refusal with {@code --fail-on-refuse}</li>
 * </ul>
 */
public final class LookmlReportCli {

  private static final String USAGE =
      "usage:\n"
      + "  lookml-report report <path> [-o report.md] [--json report.json]"
      + " [--fail-on-refuse]\n"
      + "\n"
      + "  <path> is a .lkml file or a directory of .lkml files.\n";

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
    if (opts.path == null) {
      err.println("error: report requires an input path");
      err.print(USAGE);
      return RC_BAD_ARGS;
    }

    final String lookml;
    try {
      lookml = readLookml(opts.path);
    } catch (java.nio.file.NoSuchFileException e) {
      err.println("error: cannot read " + opts.path + ": no such file");
      return RC_FAILURE;
    } catch (IOException | UncheckedIOException e) {
      err.println("error: cannot read " + opts.path + ": "
          + rootMessage(e));
      return RC_FAILURE;
    }

    final CoverageReport coverage;
    try {
      final LookmlNode doc = LookmlParser.parse(lookml);
      final TranspileResult tr = new LookmlTranspiler().transpile(doc);
      coverage = CoverageReport.from(tr);
    } catch (LookmlParseException e) {
      err.println("error: failed to parse LookML at " + opts.path + ": "
          + rootMessage(e));
      return RC_FAILURE;
    } catch (RuntimeException e) {
      err.println("error: failed to build coverage report for " + opts.path
          + ": " + rootMessage(e));
      return RC_FAILURE;
    }

    final int emitRc = emit(coverage, opts, out, err);
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
  private static int emit(CoverageReport coverage, Options opts,
      PrintStream out, PrintStream err) {
    final String markdown = new MarkdownReportWriter().write(coverage);
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
      final String json = new JsonReportWriter().write(coverage);
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

  /**
   * Reads LookML from a file, or merges every {@code .lkml} file in a
   * directory (sorted by name for determinism) into one document by
   * concatenation.
   */
  private static String readLookml(Path path) throws IOException {
    if (Files.isDirectory(path)) {
      return mergeDirectory(path);
    }
    return Files.readString(path, StandardCharsets.UTF_8);
  }

  private static String mergeDirectory(Path dir) throws IOException {
    final List<Path> files;
    try (Stream<Path> walk = Files.list(dir)) {
      files = walk
          .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
              .endsWith(LKML_SUFFIX))
          .sorted()
          .collect(Collectors.toList());
    }
    if (files.isEmpty()) {
      throw new java.nio.file.NoSuchFileException(
          dir + " contains no " + LKML_SUFFIX + " files");
    }
    final StringBuilder sb = new StringBuilder();
    for (Path f : files) {
      sb.append(Files.readString(f, StandardCharsets.UTF_8)).append('\n');
    }
    return sb.toString();
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
    private final Path markdownOut;
    private final Path jsonOut;
    private final boolean failOnRefuse;

    private Options(Path path, Path markdownOut, Path jsonOut,
        boolean failOnRefuse) {
      this.path = path;
      this.markdownOut = markdownOut;
      this.jsonOut = jsonOut;
      this.failOnRefuse = failOnRefuse;
    }

    private static Options parse(String[] args) {
      Path path = null;
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
      return new Options(path, markdownOut, jsonOut, failOnRefuse);
    }
  }
}

// End LookmlReportCli.java
