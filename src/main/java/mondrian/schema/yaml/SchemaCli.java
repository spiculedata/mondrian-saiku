/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.schema.yaml;

import mondrian.olap.MondrianDef;

import org.eigenbase.xom.DOMWrapper;
import org.eigenbase.xom.Parser;
import org.eigenbase.xom.XOMUtil;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * #34 Phase 1 Session 5: end-user CLI wrapping the YAML ↔ XML
 * schema converters with a {@code lint} subcommand for structural
 * validation. The user-discoverable surface for the whole YAML schema
 * feature.
 *
 * <h3>Subcommands</h3>
 *
 * <pre>
 *   mondrian-schema to-yaml &lt;input.xml&gt;  [-o file.yaml]
 *   mondrian-schema to-xml  &lt;input.yaml&gt; [-o file.xml]
 *   mondrian-schema lint    &lt;input.{yaml,xml}&gt;
 * </pre>
 *
 * <p>Input format is detected by extension ({@code .yaml} / {@code .yml}
 * → YAML, anything else → XML). {@code lint} forward-converts YAML to
 * XML if needed, then runs the XML through MondrianDef's XOM
 * constructor — the same validation Mondrian itself performs when
 * loading a schema. A schema that passes lint will load into Mondrian
 * (modulo cross-references to DB tables which are runtime concerns).
 *
 * <h3>Exit codes</h3>
 *
 * <ul>
 *   <li>{@code 0} — success</li>
 *   <li>{@code 1} — bad arguments / missing input file</li>
 *   <li>{@code 2} — lint or parse failure (diagnostic on stderr)</li>
 * </ul>
 *
 * <p>Use {@link #run(String[], PrintStream, PrintStream)} from tests
 * or other programs to capture streams without forking.
 */
public final class SchemaCli {

    private static final String USAGE =
        "usage:\n"
        + "  mondrian-schema to-yaml <input.xml>  [-o output.yaml]\n"
        + "  mondrian-schema to-xml  <input.yaml> [-o output.xml]\n"
        + "  mondrian-schema lint    <input.{yaml,xml}>\n";

    private SchemaCli() {}

    /** Standalone JVM entry point. Delegates to
     *  {@link #run(String[], PrintStream, PrintStream)} and exits with
     *  the returned code. */
    public static void main(String[] args) {
        int rc = run(args, System.out, System.err);
        System.exit(rc);
    }

    /**
     * Programmatic entry point — same behaviour as {@link #main} but
     * returns the exit code instead of calling {@code System.exit} and
     * lets callers inject their own output streams. Used by the test
     * harness.
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args == null || args.length == 0) {
            err.print(USAGE);
            return 1;
        }
        String sub = args[0];
        switch (sub) {
        case "to-yaml":
            return convert(args, out, err, /*toYaml*/ true);
        case "to-xml":
            return convert(args, out, err, /*toYaml*/ false);
        case "lint":
            return lint(args, out, err);
        default:
            err.println("error: unknown subcommand: " + sub);
            err.print(USAGE);
            return 1;
        }
    }

    private static int convert(
        String[] args, PrintStream out, PrintStream err, boolean toYaml)
    {
        if (args.length < 2) {
            err.println("error: " + args[0] + " requires an input file");
            err.print(USAGE);
            return 1;
        }
        Path input = Paths.get(args[1]);
        Path output = null;
        for (int i = 2; i < args.length; i++) {
            if ("-o".equals(args[i]) && i + 1 < args.length) {
                output = Paths.get(args[++i]);
            }
        }
        String converted;
        try {
            if (toYaml) {
                // XML → YAML: just slurp the file; no includes.
                String inputText = Files.readString(
                    input, StandardCharsets.UTF_8);
                converted = XmlSchemaToYaml.toYaml(inputText);
            } else {
                // YAML → XML: use path-based entry so any $ref
                // includes resolve relative to the file.
                converted = YamlSchemaConverter.toXmlFromPath(input);
            }
        } catch (java.nio.file.NoSuchFileException e) {
            err.println("error: cannot read " + input + ": no such file");
            return 1;
        } catch (java.io.IOException e) {
            err.println("error: cannot read " + input + ": " + e.getMessage());
            return 1;
        } catch (Exception e) {
            err.println("error: conversion failed: " + e.getMessage());
            return 2;
        }
        if (output != null) {
            try {
                Files.writeString(output, converted, StandardCharsets.UTF_8);
            } catch (Exception e) {
                err.println(
                    "error: cannot write " + output + ": " + e.getMessage());
                return 1;
            }
        } else {
            out.print(converted);
        }
        return 0;
    }

    private static int lint(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2) {
            err.println("error: lint requires an input file");
            err.print(USAGE);
            return 1;
        }
        Path input = Paths.get(args[1]);
        String xml;
        try {
            if (isYamlPath(input)) {
                // Path-based entry resolves any $ref includes for the
                // realistic multi-file deployment case.
                xml = YamlSchemaConverter.toXmlFromPath(input);
            } else {
                xml = Files.readString(input, StandardCharsets.UTF_8);
            }
        } catch (java.nio.file.NoSuchFileException e) {
            err.println("error: cannot read " + input + ": no such file");
            return 1;
        } catch (java.io.IOException e) {
            err.println("error: cannot read " + input + ": " + e.getMessage());
            return 1;
        } catch (Exception e) {
            err.println(
                "error: YAML → XML conversion failed: " + e.getMessage());
            return 2;
        }
        // Two-tier validation:
        //
        //   (1) XML parses cleanly via Mondrian's own XOM parser.
        //       Always required.
        //   (2) MondrianDef.Schema's modern XOM constructor accepts
        //       it — full structural validation against the modern
        //       (Mondrian 4) XSD. Skipped for legacy (Mondrian 3)
        //       schemas because the modern constructor rejects
        //       legacy enum values (e.g. type="TimeDimension" — the
        //       legacy upgrader rewrites this to "TIME" but lint
        //       doesn't run the upgrader since that needs a real
        //       RolapSchemaLoader + datasource context).
        //
        // Legacy detection mirrors RolapSchemaLoader.isLegacy /
        // hasMondrian4Elements: a schema with neither a
        // <PhysicalSchema> child nor any <Cube><MeasureGroups> child
        // is treated as Mondrian-3 legacy.
        DOMWrapper def;
        try {
            Parser xmlParser = XOMUtil.createDefaultParser();
            xmlParser.setKeepPositions(true);
            def = xmlParser.parse(xml);
        } catch (Exception e) {
            err.println("error: XML parse failed: " + e.getMessage());
            return 2;
        }
        if (looksLegacy(def)) {
            out.println("ok (legacy): " + input);
            return 0;
        }
        try {
            new MondrianDef.Schema(def);
        } catch (Exception e) {
            err.println("error: schema failed structural validation: "
                + e.getMessage());
            return 2;
        }
        out.println("ok: " + input);
        return 0;
    }

    /**
     * Mirror of {@code RolapSchemaLoader.isLegacy + hasMondrian4Elements}.
     * A schema is legacy (Mondrian 3) unless it contains
     * {@code <PhysicalSchema>} as a direct child or any
     * {@code <Cube><MeasureGroups>} grandchild.
     */
    private static boolean looksLegacy(DOMWrapper schemaDom) {
        for (DOMWrapper child : schemaDom.getChildren()) {
            if ("PhysicalSchema".equals(child.getTagName())) {
                return false;
            }
            if ("Cube".equals(child.getTagName())) {
                for (DOMWrapper grandchild : child.getChildren()) {
                    if ("MeasureGroups".equals(grandchild.getTagName())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean isYamlPath(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }
}

// End SchemaCli.java
