/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.rolap;

import mondrian.olap.MondrianDef;
import mondrian.rolap.RolapSchema.DurationSpec;
import mondrian.rolap.RolapSchema.DurationUnit;
import mondrian.rolap.RolapSchema.PhysColumn;
import mondrian.rolap.RolapSchema.PhysComputedColumn;
import mondrian.rolap.RolapSchema.PhysExpr;
import mondrian.rolap.RolapSchema.PhysRealColumn;
import mondrian.rolap.RolapSchema.PhysRelation;
import mondrian.rolap.RolapSchema.PhysTextExpr;
import mondrian.rolap.RolapSchema.TierBin;
import mondrian.rolap.RolapSchema.TierSpec;
import mondrian.spi.Dialect;

import org.eigenbase.xom.ElementDef;

import java.util.ArrayList;
import java.util.List;

/**
 * #108: synthesizes {@link PhysComputedColumn}s from native
 * {@code <Tier>} (binning) and {@code <Duration>} (date-diff) attribute
 * declarations.
 *
 * <p>Each synthesized column carries both:
 * <ul>
 *   <li>an inherited {@code list} of {@link PhysExpr} fragments that
 *       render standard SQL for the legacy (non-Calcite) path —
 *       {@code CASE WHEN col < b THEN 'label' … END} for a tier,
 *       {@code TIMESTAMPDIFF(unit, start, end)} for a duration; and</li>
 *   <li>a structured {@link RolapSchema.ComputedSpec} the Calcite adapter
 *       reads to emit a dialect-correct Rex expression instead.</li>
 * </ul>
 *
 * <p>The factory is stateless apart from the {@link RolapSchemaLoader} it
 * borrows for column resolution and error reporting; it mutates nothing
 * the caller passes in and returns fresh objects.
 */
final class RolapComputedColumnFactory {

    private final RolapSchemaLoader loader;
    private final Dialect dialect;

    RolapComputedColumnFactory(RolapSchemaLoader loader, Dialect dialect) {
        this.loader = loader;
        this.dialect = dialect;
    }

    /**
     * Build the key column for a {@code <Tier>} attribute: a CASE that
     * maps the source column into its bin label.
     *
     * @return the computed column, or null if a referenced column or bin
     *     is invalid (an error has already been posted).
     */
    PhysComputedColumn createTierKey(
        MondrianDef.Attribute xmlAttribute,
        MondrianDef.Tier xmlTier,
        PhysRelation defaultRelation)
    {
        final PhysRelation relation =
            resolveRelation(xmlAttribute, xmlTier.table, defaultRelation);
        if (relation == null) {
            return null;
        }
        final PhysColumn source =
            loader.getPhysColumn(relation, xmlTier.column, xmlTier, "column");
        if (source == null) {
            return null;
        }
        final List<TierBin> bins = parseBins(xmlTier);
        if (bins == null) {
            return null;
        }
        final List<PhysExpr> list =
            tierSql(source, bins, false /* labels */);
        return new PhysComputedColumn(
            loader,
            xmlAttribute,
            source.relation,
            xmlAttribute.name,
            Dialect.Datatype.String,
            null,
            list,
            new TierSpec(source, bins));
    }

    /**
     * Build the order-by column for a {@code <Tier>} attribute: a CASE
     * that maps the source column into its bin <em>ordinal</em> (0, 1, 2,
     * …) so members sort by boundary order, not lexically.
     */
    PhysComputedColumn createTierOrderBy(
        MondrianDef.Attribute xmlAttribute,
        MondrianDef.Tier xmlTier,
        PhysRelation defaultRelation)
    {
        final PhysRelation relation =
            resolveRelation(xmlAttribute, xmlTier.table, defaultRelation);
        if (relation == null) {
            return null;
        }
        final PhysColumn source =
            loader.getPhysColumn(relation, xmlTier.column, xmlTier, "column");
        if (source == null) {
            return null;
        }
        final List<TierBin> bins = parseBins(xmlTier);
        if (bins == null) {
            return null;
        }
        final List<PhysExpr> list =
            tierSql(source, bins, true /* ordinals */);
        return new PhysComputedColumn(
            loader,
            xmlAttribute,
            source.relation,
            xmlAttribute.name + "$ord",
            Dialect.Datatype.Integer,
            mondrian.rolap.SqlStatement.Type.INT,
            list,
            new TierSpec(source, bins));
    }

    /**
     * Build the key column for a {@code <Duration>} attribute: a
     * date-diff between the start and end columns in the declared unit.
     */
    PhysComputedColumn createDurationKey(
        MondrianDef.Attribute xmlAttribute,
        MondrianDef.Duration xmlDuration,
        PhysRelation defaultRelation)
    {
        final PhysRelation relation =
            resolveRelation(
                xmlAttribute, xmlDuration.table, defaultRelation);
        if (relation == null) {
            return null;
        }
        final PhysColumn start =
            loader.getPhysColumn(
                relation, xmlDuration.startColumn, xmlDuration,
                "startColumn");
        final PhysColumn end =
            loader.getPhysColumn(
                relation, xmlDuration.endColumn, xmlDuration, "endColumn");
        if (start == null || end == null) {
            return null;
        }
        final DurationUnit unit = parseUnit(xmlDuration);
        if (unit == null) {
            return null;
        }
        final List<PhysExpr> list = durationSql(start, end, unit);
        return new PhysComputedColumn(
            loader,
            xmlAttribute,
            start.relation,
            xmlAttribute.name,
            Dialect.Datatype.Integer,
            mondrian.rolap.SqlStatement.Type.INT,
            list,
            new DurationSpec(start, end, unit));
    }

    // ---- helpers ----

    private PhysRelation resolveRelation(
        ElementDef xml, String table, PhysRelation defaultRelation)
    {
        if (table == null) {
            if (defaultRelation == null) {
                loader.getHandler().error(
                    "Table required for computed attribute", xml, "table");
            }
            return defaultRelation;
        }
        final PhysRelation relation =
            loader.schema.physicalSchema.tablesByName.get(table);
        if (relation == null) {
            loader.getHandler().error(
                "Table '" + table + "' not found", xml, "table");
        }
        return relation;
    }

    /** Parse and validate the ordered bin list. The last bin must be
     *  open-ended (no boundary); all earlier bins must declare an
     *  ascending boundary. */
    private List<TierBin> parseBins(MondrianDef.Tier xmlTier) {
        if (xmlTier.bins == null || xmlTier.bins.length == 0) {
            loader.getHandler().error(
                "Tier must declare at least one Bin", xmlTier, null);
            return null;
        }
        final List<TierBin> bins = new ArrayList<TierBin>();
        Double previousBoundary = null;
        for (int i = 0; i < xmlTier.bins.length; i++) {
            final MondrianDef.Bin bin = xmlTier.bins[i];
            final boolean last = i == xmlTier.bins.length - 1;
            if (bin.label == null) {
                loader.getHandler().error(
                    "Bin must have a label", bin, "label");
                return null;
            }
            if (last) {
                if (bin.boundary != null) {
                    loader.getHandler().error(
                        "The final Bin must be open-ended (no boundary)",
                        bin, "boundary");
                    return null;
                }
                bins.add(new TierBin(null, bin.label));
                continue;
            }
            if (bin.boundary == null) {
                loader.getHandler().error(
                    "Only the final Bin may omit its boundary",
                    bin, "boundary");
                return null;
            }
            final double boundary;
            try {
                boundary = Double.parseDouble(bin.boundary.trim());
            } catch (NumberFormatException e) {
                loader.getHandler().error(
                    "Bin boundary '" + bin.boundary + "' is not numeric",
                    bin, "boundary");
                return null;
            }
            if (previousBoundary != null && boundary <= previousBoundary) {
                loader.getHandler().error(
                    "Bin boundaries must ascend; '" + bin.boundary
                    + "' is not greater than the previous boundary",
                    bin, "boundary");
                return null;
            }
            previousBoundary = boundary;
            bins.add(new TierBin(numberOf(bin.boundary), bin.label));
        }
        return bins;
    }

    /** Render a number from its textual boundary, preferring an integer
     *  when the value has no fractional part (so member captions read
     *  '10' not '10.0' on the legacy SQL path). */
    private static Number numberOf(String text) {
        final String s = text.trim();
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException ignored) {
            return Double.valueOf(s);
        }
    }

    private DurationUnit parseUnit(MondrianDef.Duration xmlDuration) {
        final String raw = xmlDuration.unit == null
            ? "DAY"
            : xmlDuration.unit.trim().toUpperCase();
        try {
            return DurationUnit.valueOf(raw);
        } catch (IllegalArgumentException e) {
            loader.getHandler().error(
                "Unknown duration unit '" + xmlDuration.unit + "'",
                xmlDuration, "unit");
            return null;
        }
    }

    /**
     * Build the legacy-SQL {@link PhysExpr} list for a tier CASE.
     *
     * @param ordinals when true, each THEN/ELSE result is the bin ordinal
     *     (0, 1, 2, …) for order-by; when false, the quoted bin label.
     */
    private List<PhysExpr> tierSql(
        PhysColumn source, List<TierBin> bins, boolean ordinals)
    {
        // CASE WHEN <col> < b0 THEN r0 WHEN <col> < b1 THEN r1 …
        //   ELSE rLast END
        final List<PhysExpr> list = new ArrayList<PhysExpr>();
        list.add(new PhysTextExpr("CASE"));
        for (int i = 0; i < bins.size(); i++) {
            final TierBin bin = bins.get(i);
            final String result = ordinals
                ? Integer.toString(i)
                : quote(bin.label);
            if (bin.boundary == null) {
                list.add(new PhysTextExpr(" ELSE " + result));
            } else {
                list.add(new PhysTextExpr(" WHEN "));
                list.add(refOf(source));
                list.add(new PhysTextExpr(
                    " < " + bin.boundary + " THEN " + result));
            }
        }
        list.add(new PhysTextExpr(" END"));
        return list;
    }

    private List<PhysExpr> durationSql(
        PhysColumn start, PhysColumn end, DurationUnit unit)
    {
        // TIMESTAMPDIFF(<unit>, <start>, <end>) — the SQL-standard form,
        // which H2 (and the legacy fallback dialects we target) accept.
        // The Calcite path re-renders this per dialect from the spec.
        final List<PhysExpr> list = new ArrayList<PhysExpr>();
        list.add(new PhysTextExpr("TIMESTAMPDIFF(" + unit.name() + ", "));
        list.add(refOf(start));
        list.add(new PhysTextExpr(", "));
        list.add(refOf(end));
        list.add(new PhysTextExpr(")"));
        return list;
    }

    /** A {@link PhysExpr} that renders the column reference. Real columns
     *  render directly; anything else (already-computed) defers to its own
     *  {@code toSql()} via a text fragment. */
    private static PhysExpr refOf(PhysColumn column) {
        if (column instanceof PhysRealColumn) {
            return column;
        }
        return new PhysTextExpr(column.toSql());
    }

    private String quote(String literal) {
        final StringBuilder buf = new StringBuilder();
        dialect.quoteStringLiteral(buf, literal);
        return buf.toString();
    }
}
