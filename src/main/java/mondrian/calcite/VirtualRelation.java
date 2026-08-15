/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.calcite;

import mondrian.rolap.RolapSchema;
import mondrian.spi.Dialect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A relation a Mondrian schema declares that does <em>not</em> exist in the
 * database: an {@code <InlineTable>} (literal rows) or a {@code <View>} /
 * {@code <Query>} (a SQL string).
 *
 * <p>The Calcite backend reflects the database through {@code JdbcSchema},
 * so these relations are invisible to it and every plan that touches one
 * fails with {@code Table 'x' not found}. {@link VirtualRelationTable} turns
 * an instance of this class into a Calcite table so the planner can scan it;
 * the scan never reaches the database as a table reference, because the
 * table expands to the parsed form of its defining SQL.
 *
 * <p>Instances are immutable and carry a {@link #digest()} so
 * {@code CalcitePlannerCache} can key a cached planner on the set of virtual
 * relations it was built with. Two Mondrian schemas that declare different
 * inline tables under the same alias must not share a planner.
 */
public final class VirtualRelation {

    public enum Kind {
        /** Literal rows, rendered to SQL by the dialect. */
        INLINE,
        /** A SQL string the schema author wrote. */
        VIEW
    }

    public final Kind kind;
    /** Alias the Mondrian schema refers to this relation by; also the name
     *  the Calcite table is registered under, because that is the name
     *  {@code CalciteSqlPlanner.build} scans. */
    public final String alias;
    /** Reserved for a future representation that does not go through SQL
     *  text; empty today. */
    public final Map<String, Dialect.Datatype> columns;
    /** Reserved, as {@link #columns}; empty today. */
    public final List<String[]> rows;
    /** The SQL defining the relation: what the schema author wrote for a
     *  {@link Kind#VIEW}, or what {@code Dialect.generateInline} rendered the
     *  literal rows to for a {@link Kind#INLINE}. */
    public final String sql;

    private VirtualRelation(
        Kind kind,
        String alias,
        Map<String, Dialect.Datatype> columns,
        List<String[]> rows,
        String sql)
    {
        this.kind = kind;
        this.alias = alias;
        this.columns = Collections.unmodifiableMap(columns);
        this.rows = Collections.unmodifiableList(rows);
        this.sql = sql;
    }

    /**
     * Extracts every virtual relation declared by a Mondrian schema.
     *
     * <p>Relations backed by real database tables ({@code PhysTable}) are
     * skipped -- {@code JdbcSchema} already reflects those.
     *
     * @param schema Mondrian schema; may be null
     * @return virtual relations, in declaration order; never null
     */
    public static List<VirtualRelation> fromSchema(RolapSchema schema) {
        if (schema == null || schema.getPhysicalSchema() == null) {
            return Collections.emptyList();
        }
        final List<VirtualRelation> out = new ArrayList<VirtualRelation>();
        for (RolapSchema.PhysRelation rel
            : schema.getPhysicalSchema().getRelations())
        {
            final VirtualRelation vr = from(rel, schema.getDialect());
            if (vr != null) {
                out.add(vr);
            }
        }
        return out;
    }

    /**
     * @param rel Physical relation
     * @param dialect Dialect the inline rows are rendered for
     * @return the virtual relation {@code rel} describes, or null if it is
     *     backed by a real database table
     */
    public static VirtualRelation from(
        RolapSchema.PhysRelation rel, Dialect dialect)
    {
        if (rel instanceof RolapSchema.PhysInlineTable) {
            // Render the literal rows through the dialect, exactly as
            // SqlQuery.addFrom does for the legacy SQL generator, so both
            // backends produce equivalent SQL for the same inline table.
            // Going through Calcite's own VALUES would be tidier, but the
            // dialects disagree about VALUES far more than they disagree
            // about "SELECT literal ... UNION ALL", and Mondrian already
            // encodes each database's answer in Dialect.generateInline.
            return new VirtualRelation(
                Kind.INLINE,
                rel.getAlias(),
                Collections.<String, Dialect.Datatype>emptyMap(),
                Collections.<String[]>emptyList(),
                mondrian.rolap.RolapUtil.convertInlineTableToRelation(
                    (RolapSchema.PhysInlineTable) rel, dialect)
                    .getSqlString());
        }
        if (rel instanceof RolapSchema.PhysView) {
            return new VirtualRelation(
                Kind.VIEW,
                rel.getAlias(),
                Collections.<String, Dialect.Datatype>emptyMap(),
                Collections.<String[]>emptyList(),
                ((RolapSchema.PhysView) rel).getSqlString());
        }
        return null;
    }

    /**
     * Stable identity of this relation's <em>content</em>, not its object
     * identity. Used to key cached planners: relations that render the same
     * SQL are interchangeable, relations that do not must not share a cache
     * entry.
     *
     * @return digest
     */
    public String digest() {
        final StringBuilder buf = new StringBuilder();
        buf.append(kind).append(':').append(alias).append('(');
        for (Map.Entry<String, Dialect.Datatype> e : columns.entrySet()) {
            buf.append(e.getKey()).append(' ').append(e.getValue()).append(',');
        }
        buf.append(')');
        if (sql != null) {
            buf.append('=').append(sql);
        }
        for (String[] row : rows) {
            buf.append('[');
            for (String v : row) {
                buf.append(v).append('|');
            }
            buf.append(']');
        }
        return buf.toString();
    }

    /**
     * @param relations Virtual relations
     * @return a digest of the whole collection, empty string when there are
     *     none (so schemas with no virtual relations keep sharing a planner)
     */
    public static String digestOf(List<VirtualRelation> relations) {
        if (relations == null || relations.isEmpty()) {
            return "";
        }
        final List<String> digests = new ArrayList<String>();
        for (VirtualRelation vr : relations) {
            digests.add(vr.digest());
        }
        Collections.sort(digests);
        // A hash keeps the cache key small; collisions across a handful of
        // schemas in one JVM are not a practical concern, and a collision
        // would only mean two identical-looking schemas share a planner.
        return Integer.toHexString(digests.hashCode())
            + "/" + digests.size();
    }

    @Override
    public String toString() {
        return digest();
    }
}

// End VirtualRelation.java
