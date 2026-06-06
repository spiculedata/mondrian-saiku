/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.olap;

/**
 * #106: an immutable predicate-based row-security grant.
 *
 * <p>A predicate grant restricts the fact rows of a measure group to those
 * whose real fact {@link #getColumn() column} satisfies an equality / IN
 * comparison against the validated value of a bounded {@link #getParameter()
 * query-context parameter} (a #105 {@code <QueryParameter>}). The Calcite
 * adapter injects it as a filter on every segment load touching the measure
 * group, pre-aggregation, so aggregate totals are correctly restricted.
 *
 * <p>This object holds only the (already-validated) schema-side coordinates of
 * the grant. The bound value is resolved per-request through
 * {@link mondrian.calcite.QueryParameterContext}; it is never stored here, so
 * one immutable grant is safely shared across all connections/users.
 */
public final class PredicateGrant {

    /** Comparison operator for a predicate grant. */
    public enum Operator {
        /** {@code column = <param>}. */
        EQ,
        /** {@code column IN (<param tokens>)}. */
        IN;

        /**
         * Parses the schema {@code operator} attribute. Null/blank defaults to
         * {@link #EQ} (matching the XOM default).
         *
         * @param raw the raw attribute text
         * @return the parsed operator
         * @throws MondrianException on an unrecognised operator
         */
        public static Operator parse(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return EQ;
            }
            switch (raw.trim().toLowerCase()) {
            case "eq":
                return EQ;
            case "in":
                return IN;
            default:
                throw new MondrianException(
                    "Unknown <PredicateGrant> operator '" + raw
                    + "'; expected 'eq' or 'in'.");
            }
        }
    }

    private final String measureGroup;
    private final String column;
    private final Operator operator;
    private final String parameter;

    /**
     * Creates a predicate grant. All coordinates are validated by the schema
     * loader before construction; this constructor only enforces non-null.
     *
     * @param measureGroup measure group whose fact rows are restricted
     * @param column real fact column the predicate is applied to
     * @param operator comparison operator
     * @param parameter bound #105 query-context parameter name
     */
    public PredicateGrant(
        String measureGroup,
        String column,
        Operator operator,
        String parameter)
    {
        if (measureGroup == null || measureGroup.trim().isEmpty()) {
            throw new MondrianException(
                "<PredicateGrant> requires a non-empty measureGroup.");
        }
        if (column == null || column.trim().isEmpty()) {
            throw new MondrianException(
                "<PredicateGrant> requires a non-empty column.");
        }
        if (parameter == null || parameter.trim().isEmpty()) {
            throw new MondrianException(
                "<PredicateGrant> requires a non-empty parameter.");
        }
        if (operator == null) {
            throw new MondrianException(
                "<PredicateGrant> requires an operator.");
        }
        this.measureGroup = measureGroup;
        this.column = column;
        this.operator = operator;
        this.parameter = parameter;
    }

    /** @return the restricted measure group's name. */
    public String getMeasureGroup() {
        return measureGroup;
    }

    /** @return the real fact column the predicate is applied to. */
    public String getColumn() {
        return column;
    }

    /** @return the comparison operator. */
    public Operator getOperator() {
        return operator;
    }

    /** @return the bound query-context parameter name. */
    public String getParameter() {
        return parameter;
    }

    @Override
    public String toString() {
        return "PredicateGrant{measureGroup=" + measureGroup
            + ", column=" + column
            + ", operator=" + operator
            + ", parameter=" + parameter + "}";
    }
}
