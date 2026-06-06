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

import mondrian.calcite.QueryParameterContext;
import mondrian.olap.MondrianException;
import mondrian.olap.PredicateGrant;
import mondrian.olap.Role;
import mondrian.spi.Dialect;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * #106 (SECURITY): renders a role's predicate row-security grants as raw-SQL
 * {@code WHERE} fragments for the legacy SQL generator (drill-through).
 *
 * <p>The aggregate segment-load path enforces predicate grants at the Calcite
 * chokepoint ({@code CalcitePlannerAdapters.injectPredicateGrants}). Drill-
 * through, however, goes through a SEPARATE legacy SQL path
 * ({@code DrillThroughQuerySpec}) that the Calcite injection never sees. Without
 * this helper a row-secured user who drills through would receive raw fact rows
 * for EVERY tenant — a data leak. This class is the drill-through counterpart of
 * the Calcite injection: it resolves the same {@link PredicateGrant}s against
 * the same {@link QueryParameterContext} and emits an equivalent EQ/IN
 * predicate on the real fact column, with the SAME fail-closed contract.
 *
 * <p><b>Fail-closed contract</b> (mirrors {@code applyPredicateGrant}):
 * <ul>
 *   <li>A grant applies but its bound parameter is undeclared / unbound /
 *       unresolvable → throw {@link MondrianException}. The drill-through SQL is
 *       never emitted unfiltered.</li>
 *   <li>An IN grant whose resolved value set is empty → emit a universally-false
 *       predicate ({@code 1 = 0}) so zero rows are returned.</li>
 *   <li>The grant column is not a real fact column on the measure group's fact
 *       relation (should never happen — validated at load) → universally-false.
 *       </li>
 * </ul>
 *
 * <p>Every literal is rendered through the {@link Dialect}'s
 * {@code quoteStringLiteral} / {@code quoteNumericLiteral} so a string value is
 * a quoted literal, never executable SQL — the same no-injection guarantee the
 * Calcite literal substitution provides. Values are sourced from the validated
 * {@link QueryParameterContext} (type-coerced, allowed-set-checked).
 */
public final class PredicateGrantSqlFilter {

    private PredicateGrantSqlFilter() {}

    /** A SQL fragment that matches no rows. */
    private static final String UNIVERSAL_FALSE = "1 = 0";

    /**
     * Builds the list of WHERE fragments enforcing the active role's predicate
     * grants on the measure groups this drill-through touches. Returns an empty
     * list (no restriction) when the load is unsecured: no role, the role has no
     * predicate grants, or no touched measure group is secured.
     *
     * @param star the drill-through's star (anchors the touched measure groups)
     * @param measure the drill-through's primary star measure
     * @param role the connection's active role (may be null)
     * @param paramContext the connection's resolved query-parameter context
     *     (may be null)
     * @param dialect the SQL dialect used to quote literals safely
     * @return immutable list of SQL boolean fragments to AND into the WHERE
     * @throws MondrianException if a grant applies but cannot be resolved
     *     (fail-closed)
     */
    public static List<String> whereFragments(
        RolapStar star,
        RolapStar.Measure measure,
        Role role,
        QueryParameterContext paramContext,
        Dialect dialect)
    {
        if (role == null || !role.hasPredicateGrants() || measure == null) {
            return java.util.Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        for (RolapMeasureGroup mg : touchedMeasureGroups(star, measure)) {
            String key = predicateGrantKey(
                mg.getCube().getName(), mg.getName());
            for (PredicateGrant grant : role.getPredicateGrants(key)) {
                // Union roles concatenate constituent grants; ANDing each here
                // yields the conservative most-restrictive union (see
                // UnionRoleImpl.getPredicateGrants).
                out.add(renderGrant(mg, grant, paramContext, dialect));
            }
        }
        return java.util.Collections.unmodifiableList(out);
    }

    /**
     * MUST mirror {@code CalcitePlannerAdapters.predicateGrantKey} and
     * {@code RolapSchemaLoader.predicateGrantKey} so a grant declared at load
     * time keys to the live measure group at render time.
     */
    private static String predicateGrantKey(
        String cubeName, String measureGroupName)
    {
        return cubeName + "." + measureGroupName;
    }

    /**
     * The distinct measure groups whose fact rows this drill-through
     * aggregates. Resolved from the measure's owning cube — the same path
     * {@code CalcitePlannerAdapters.touchedMeasureGroups} uses, restricted to
     * the single drill-through measure.
     */
    private static Set<RolapMeasureGroup> touchedMeasureGroups(
        RolapStar star, RolapStar.Measure measure)
    {
        Set<RolapMeasureGroup> out = new LinkedHashSet<RolapMeasureGroup>();
        String cubeName = measure.getCubeName();
        if (cubeName == null) {
            return out;
        }
        mondrian.olap.Cube cube = star.getSchema().lookupCube(cubeName, false);
        if (!(cube instanceof RolapCube)) {
            return out;
        }
        for (RolapMeasureGroup mg : ((RolapCube) cube).getMeasureGroups()) {
            if (measureGroupOwnsMeasure(mg, measure)) {
                out.add(mg);
            }
        }
        return out;
    }

    /**
     * Whether {@code mg}'s fact relation is the table the star measure's
     * expression reads from. Mirrors
     * {@code CalcitePlannerAdapters.measureGroupOwnsMeasure}.
     */
    private static boolean measureGroupOwnsMeasure(
        RolapMeasureGroup mg, RolapStar.Measure measure)
    {
        RolapSchema.PhysColumn expr = measure.getExpression();
        if (expr == null) {
            return measure.getTable() != null
                && measure.getTable().getRelation() == mg.getFactRelation();
        }
        return expr.relation == mg.getFactRelation();
    }

    /**
     * Renders one predicate grant as a SQL boolean fragment on the real fact
     * column. Fails closed per the class contract.
     */
    private static String renderGrant(
        RolapMeasureGroup mg,
        PredicateGrant grant,
        QueryParameterContext paramContext,
        Dialect dialect)
    {
        // Fail closed: an undeclared/absent parameter context means we cannot
        // bind a value — deny rather than leave the fact unrestricted.
        if (paramContext == null
            || !paramContext.isDeclared(grant.getParameter()))
        {
            throw new MondrianException(
                "Predicate row-security grant on measure group '"
                + grant.getMeasureGroup() + "' references query parameter '"
                + grant.getParameter() + "', which is not bound for this "
                + "connection; refusing to emit an unfiltered drill-through "
                + "(fail-closed).");
        }
        RolapSchema.PhysRelation factRelation = mg.getFactRelation();
        RolapSchema.PhysColumn col =
            factRelation.getColumn(grant.getColumn(), false);
        if (!(col instanceof RolapSchema.PhysRealColumn)) {
            // Defensive: load-time validation should prevent this. Fail closed.
            return UNIVERSAL_FALSE;
        }
        String columnSql = col.toSql();
        switch (grant.getOperator()) {
        case IN:
            return renderIn(columnSql, grant, paramContext, dialect);
        case EQ:
        default:
            return renderEq(columnSql, grant, paramContext, dialect);
        }
    }

    private static String renderEq(
        String columnSql,
        PredicateGrant grant,
        QueryParameterContext paramContext,
        Dialect dialect)
    {
        Object value = resolveOrFail(grant, paramContext, /*list=*/ false);
        StringBuilder sb = new StringBuilder(columnSql).append(" = ");
        appendLiteral(sb, value, dialect);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String renderIn(
        String columnSql,
        PredicateGrant grant,
        QueryParameterContext paramContext,
        Dialect dialect)
    {
        List<Object> values =
            (List<Object>) resolveOrFail(grant, paramContext, /*list=*/ true);
        if (values.isEmpty()) {
            // Empty IN set is deny-all (fail closed), matching the Calcite path.
            return UNIVERSAL_FALSE;
        }
        StringBuilder sb = new StringBuilder(columnSql).append(" IN (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            appendLiteral(sb, values.get(i), dialect);
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * Resolves the grant's bound parameter value(s) through the validated
     * context, translating any resolution failure into a fail-closed
     * {@link MondrianException} rather than letting an unfiltered query slip
     * through.
     */
    private static Object resolveOrFail(
        PredicateGrant grant,
        QueryParameterContext paramContext,
        boolean list)
    {
        try {
            return list
                ? paramContext.resolveList(grant.getParameter())
                : paramContext.resolve(grant.getParameter());
        } catch (RuntimeException ex) {
            throw new MondrianException(
                "Predicate row-security grant on measure group '"
                + grant.getMeasureGroup() + "' could not resolve query "
                + "parameter '" + grant.getParameter()
                + "'; refusing to emit an unfiltered drill-through "
                + "(fail-closed).",
                ex);
        }
    }

    /**
     * Appends a value as a dialect-quoted SQL literal — a string is single-
     * quoted (and internally escaped), a number is rendered numerically. This
     * is the no-injection seam: a hostile string value becomes a quoted literal
     * that matches zero rows, never executable SQL.
     */
    private static void appendLiteral(
        StringBuilder sb, Object value, Dialect dialect)
    {
        if (value instanceof Number) {
            dialect.quoteNumericLiteral(sb, (Number) value);
        } else {
            dialect.quoteStringLiteral(sb, String.valueOf(value));
        }
    }
}
