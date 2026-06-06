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

import mondrian.olap.MondrianException;
import mondrian.rolap.RolapQueryParameterDef;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * #105: per-request resolved query-parameter context.
 *
 * <p>Built once per connection from the {@code session.<name>} connection
 * properties (the same channel dynamic roles use), with each value coerced to
 * its declared type and checked against the allowed-value set. After
 * construction every value held here is already validated, so the downstream
 * substitution seam ({@code CalciteSqlPlanner.eqOrIsNull}) can drop a resolved
 * value straight into a Calcite literal without further checking.
 *
 * <p>This is the clean resolver interface concurrent issue #106 (predicate
 * row-security) consumes:
 * <pre>
 *   Object   resolve(String name)     // validated typed value (single)
 *   Datatype datatypeOf(String name)  // declared type
 *   boolean  isDeclared(String name)
 * </pre>
 *
 * <p>Immutable: the resolved map is fixed at construction.
 */
public final class QueryParameterContext {

    /** The empty context — no declared parameters. */
    public static final QueryParameterContext EMPTY =
        new QueryParameterContext(Collections.emptyMap());

    /** name -> (def, resolved typed value). */
    private final Map<String, Resolved> resolved;

    private static final class Resolved {
        final RolapQueryParameterDef def;
        final Object value;
        Resolved(RolapQueryParameterDef def, Object value) {
            this.def = def;
            this.value = value;
        }
    }

    private QueryParameterContext(Map<String, Resolved> resolved) {
        this.resolved = resolved;
    }

    /**
     * Resolves every declared parameter against the supplied session values,
     * applying defaults for absent parameters. Validation failures (wrong
     * type, out of set, or a required-but-absent parameter with no default)
     * throw a {@link MondrianException}.
     *
     * @param defs declared parameter definitions (name -> def)
     * @param sessionValues raw {@code session.<name>} values harvested from
     *     the connection (name -> raw string); never null
     * @return an immutable resolved context
     */
    public static QueryParameterContext resolveAll(
        Map<String, RolapQueryParameterDef> defs,
        Map<String, String> sessionValues)
    {
        if (defs == null || defs.isEmpty()) {
            return EMPTY;
        }
        Map<String, Resolved> out = new LinkedHashMap<>();
        for (Map.Entry<String, RolapQueryParameterDef> e : defs.entrySet()) {
            String name = e.getKey();
            RolapQueryParameterDef def = e.getValue();
            String raw = sessionValues == null
                ? null : sessionValues.get(name);
            Object value;
            if (raw != null) {
                value = def.validate(raw);
            } else if (def.hasDefault()) {
                value = def.getDefaultValue();
            } else {
                throw new MondrianException(
                    "Query parameter '" + name
                    + "' has no session value and no default; supply a "
                    + "session." + name + " connection property.");
            }
            out.put(name, new Resolved(def, value));
        }
        return new QueryParameterContext(Collections.unmodifiableMap(out));
    }

    /**
     * Resolver interface (consumed by #105 filter substitution and #106
     * predicate row-security): the validated, typed value for a declared
     * parameter.
     *
     * @param name parameter name
     * @return the coerced, validated value (never null for a declared param)
     * @throws MondrianException if the parameter is not declared
     */
    public Object resolve(String name) {
        Resolved r = resolved.get(name);
        if (r == null) {
            throw new MondrianException(
                "Query parameter '" + name + "' is not declared in the "
                + "schema.");
        }
        return r.value;
    }

    /**
     * The declared type of a parameter (consumed by #106 to shape the
     * predicate it emits).
     *
     * @throws MondrianException if the parameter is not declared
     */
    public RolapQueryParameterDef.Datatype datatypeOf(String name) {
        Resolved r = resolved.get(name);
        if (r == null) {
            throw new MondrianException(
                "Query parameter '" + name + "' is not declared in the "
                + "schema.");
        }
        return r.def.getDatatype();
    }

    public boolean isDeclared(String name) {
        return resolved.containsKey(name);
    }

    public boolean isEmpty() {
        return resolved.isEmpty();
    }
}
