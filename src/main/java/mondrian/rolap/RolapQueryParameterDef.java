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

import mondrian.olap.MondrianException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * #105: schema-side definition of a bounded, typed query-context parameter
 * (a {@code <QueryParameter>} element).
 *
 * <p>This is the security boundary for the enumerated-substitution approach:
 * a supplied value is {@link #coerce coerced} to the declared {@link Datatype}
 * and then {@link #validate checked} against the optional closed allowed-value
 * set <em>before</em> it can ever become a Calcite literal. There is no
 * free-form SQL templating — the no-injection guarantee comes from strict
 * typing + enumeration, not from a JDBC placeholder.
 *
 * <p>Instances are immutable and shared across all connections to the schema.
 * Per-request resolved values live in
 * {@link mondrian.calcite.QueryParameterContext}.
 */
public final class RolapQueryParameterDef {

    /** Declared parameter types. {@code MEMBER} resolves to a member leaf
     *  key and is treated as a string key for substitution purposes. */
    public enum Datatype {
        STRING, NUMERIC, DATE, MEMBER;

        static Datatype parse(String raw) {
            if (raw == null) {
                return STRING;
            }
            switch (raw.trim()) {
            case "String":
                return STRING;
            case "Numeric":
                return NUMERIC;
            case "Date":
                return DATE;
            case "Member":
                return MEMBER;
            default:
                throw new MondrianException(
                    "Unknown <QueryParameter> type '" + raw
                    + "'; expected one of String, Numeric, Date, Member.");
            }
        }
    }

    private final String name;
    private final Datatype datatype;
    /** Validated, coerced default value; null when no default declared. */
    private final Object defaultValue;
    /** Closed allowed-value set (coerced); empty when unconstrained. */
    private final Set<Object> allowedValues;

    private RolapQueryParameterDef(
        String name,
        Datatype datatype,
        Object defaultValue,
        Set<Object> allowedValues)
    {
        this.name = name;
        this.datatype = datatype;
        this.defaultValue = defaultValue;
        this.allowedValues = allowedValues;
    }

    /**
     * Builds a definition from raw schema strings, coercing and validating
     * the default value and the allowed-value enumeration at load time so a
     * misconfigured schema fails loudly rather than at query time.
     *
     * @param name parameter name (required, unique within the schema)
     * @param typeText declared type text ({@code String|Numeric|Date|Member})
     * @param defaultText default literal, or null
     * @param allowedTexts raw allowed-value literals (may be empty/null)
     */
    public static RolapQueryParameterDef create(
        String name,
        String typeText,
        String defaultText,
        Iterable<String> allowedTexts)
    {
        if (name == null || name.trim().isEmpty()) {
            throw new MondrianException(
                "<QueryParameter> requires a non-empty name.");
        }
        Datatype datatype = Datatype.parse(typeText);

        Set<Object> allowed = new LinkedHashSet<>();
        if (allowedTexts != null) {
            for (String raw : allowedTexts) {
                allowed.add(coerceOrThrow(name, datatype, raw));
            }
        }
        Set<Object> allowedImmutable = allowed.isEmpty()
            ? Collections.emptySet()
            : Collections.unmodifiableSet(allowed);

        Object coercedDefault = null;
        if (defaultText != null) {
            coercedDefault = coerceOrThrow(name, datatype, defaultText);
            // A declared default must itself satisfy the allowed set.
            if (!allowedImmutable.isEmpty()
                && !allowedImmutable.contains(coercedDefault))
            {
                throw outOfSet(name, defaultText, allowedImmutable);
            }
        }
        return new RolapQueryParameterDef(
            name, datatype, coercedDefault, allowedImmutable);
    }

    public String getName() {
        return name;
    }

    public Datatype getDatatype() {
        return datatype;
    }

    /** @return coerced default value, or null when none declared. */
    public Object getDefaultValue() {
        return defaultValue;
    }

    public boolean hasDefault() {
        return defaultValue != null;
    }

    /** @return immutable allowed-value set; empty when unconstrained. */
    public Set<Object> getAllowedValues() {
        return allowedValues;
    }

    /**
     * Coerces a raw supplied value to this parameter's declared type and
     * checks it against the allowed-value set. The single validation entry
     * point used at request time.
     *
     * @param rawValue raw value from {@code session.<name>}
     * @return the coerced, validated typed value
     * @throws MondrianException on wrong type or out-of-set value
     */
    public Object validate(String rawValue) {
        Object coerced = coerceOrThrow(name, datatype, rawValue);
        if (!allowedValues.isEmpty() && !allowedValues.contains(coerced)) {
            throw outOfSet(name, rawValue, allowedValues);
        }
        return coerced;
    }

    private static Object coerceOrThrow(
        String name, Datatype datatype, String raw)
    {
        if (raw == null) {
            throw new MondrianException(
                "Query parameter '" + name + "' value is null.");
        }
        String trimmed = raw.trim();
        switch (datatype) {
        case STRING:
        case MEMBER:
            return trimmed;
        case NUMERIC:
            try {
                return new BigDecimal(trimmed);
            } catch (NumberFormatException e) {
                throw wrongType(name, raw, "Numeric");
            }
        case DATE:
            try {
                return LocalDate.parse(trimmed).toString();
            } catch (DateTimeParseException e) {
                throw wrongType(name, raw, "Date (ISO-8601 yyyy-MM-dd)");
            }
        default:
            throw new MondrianException(
                "Unhandled query-parameter type " + datatype);
        }
    }

    private static MondrianException wrongType(
        String name, String raw, String expected)
    {
        return new MondrianException(
            "Query parameter '" + name + "' value '" + raw
            + "' is not a valid " + expected + " value.");
    }

    private static MondrianException outOfSet(
        String name, String raw, Set<Object> allowed)
    {
        return new MondrianException(
            "Query parameter '" + name + "' value '" + raw
            + "' is not in the allowed set " + allowed + ".");
    }
}
