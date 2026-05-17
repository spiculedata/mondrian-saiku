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
        String inputText;
        try {
            inputText = Files.readString(input, StandardCharsets.UTF_8);
        } catch (Exception e) {
            err.println("error: cannot read " + input + ": " + e.getMessage());
            return 1;
        }
        String converted;
        try {
            converted = toYaml
                ? XmlSchemaToYaml.toYaml(inputText)
                : YamlSchemaConverter.toXml(inputText);
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
        String inputText;
        try {
            inputText = Files.readString(input, StandardCharsets.UTF_8);
        } catch (Exception e) {
            err.println("error: cannot read " + input + ": " + e.getMessage());
            return 1;
        }
        String xml;
        try {
            xml = isYamlPath(input)
                ? YamlSchemaConverter.toXml(inputText)
                : inputText;
        } catch (Exception e) {
            err.println(
                "error: YAML → XML conversion failed: " + e.getMessage());
            return 2;
        }
        // Run the XML through Mondrian's own XOM-backed parser. If
        // MondrianDef.Schema's constructor accepts it, the schema is
        // structurally valid (element/attribute names, nesting,
        // required fields). Runtime concerns like "does this table
        // exist in the DB" aren't checked.
        try {
            Parser xmlParser = XOMUtil.createDefaultParser();
            xmlParser.setKeepPositions(true);
            DOMWrapper def = xmlParser.parse(xml);
            new MondrianDef.Schema(def);
        } catch (Exception e) {
            err.println("error: schema failed structural validation: "
                + e.getMessage());
            return 2;
        }
        out.println("ok: " + input);
        return 0;
    }

    private static boolean isYamlPath(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }
}

// End SchemaCli.java
