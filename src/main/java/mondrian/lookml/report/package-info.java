/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/

/**
 * LookML import coverage / refusal report (issue #102): the importer's primary
 * deliverable — "the product is the coverage report as much as the cube".
 *
 * <p>{@link mondrian.lookml.report.CoverageReport#from} joins the classifier's
 * per-construct CLEAN/DEGRADE/REFUSE records with the transpiler's
 * {@link mondrian.lookml.transpile.ProvenanceMap} so every ported construct
 * carries the M4 element it produced and every refusal carries its precise
 * structural reason. {@link mondrian.lookml.report.SummaryMetrics} computes the
 * headline clean/degrade/refuse ratio at explore and field granularity;
 * {@link mondrian.lookml.report.MarkdownReportWriter} and
 * {@link mondrian.lookml.report.JsonReportWriter} render the human- and
 * machine-readable forms, and {@link mondrian.lookml.report.LookmlReportCli}
 * is the end-user CLI (with a {@code --fail-on-refuse} CI gate).
 */
package mondrian.lookml.report;

// End package-info.java
