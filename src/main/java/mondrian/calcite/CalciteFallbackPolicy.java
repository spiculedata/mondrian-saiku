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

import java.util.Locale;

/**
 * Decides whether a Calcite translation failure may fall back to the legacy
 * SQL generator.
 *
 * <p>Two different failures reach the fallback sites, and they have always
 * been treated differently:
 *
 * <ul>
 *   <li>an {@link UnsupportedTranslation} — the translator's explicit "I do
 *       not model this request shape". Historically this ALWAYS fell back,
 *       regardless of strict mode.</li>
 *   <li>any other {@code RuntimeException} or {@code AssertionError} — an
 *       unknown Calcite failure. Strict mode (on by default) rethrows these
 *       so bugs surface instead of being masked by legacy.</li>
 * </ul>
 *
 * <p>Silently falling back is only a kindness when legacy can actually answer
 * the query. On a database the legacy generator has no dialect for, the
 * fallback does not degrade — it fails, and it fails as a confusing dialect
 * error rather than as "this shape is not translated yet". Deployments that
 * depend on the Calcite path therefore need a way to say <em>never use
 * legacy</em>, which is what {@code full} provides.
 *
 * <p>{@code -Dmondrian.calcite.strict=}
 * <table>
 *   <tr><td>{@code full}</td>
 *       <td>No fallback at all. An unsupported shape is a hard error naming
 *           the shape, so coverage gaps are visible rather than silent.</td></tr>
 *   <tr><td>{@code true} (default)</td>
 *       <td>Unknown failures are fatal; an unsupported shape falls back.</td></tr>
 *   <tr><td>{@code false}</td>
 *       <td>Everything falls back, including unknown failures.</td></tr>
 * </table>
 *
 * <p>Running a test suite under {@code full} measures how much of the corpus
 * the Calcite translator actually covers: every failure is a shape that would
 * not work on a legacy-unsupported database.
 */
public final class CalciteFallbackPolicy {

    public static final String STRICT_PROPERTY = "mondrian.calcite.strict";

    private CalciteFallbackPolicy() {
    }

    private static String strictValue() {
        final String raw = System.getProperty(STRICT_PROPERTY);
        return raw == null ? "true" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a known translator gap ({@link UnsupportedTranslation}) may be
     * served by the legacy SQL generator instead.
     *
     * @return false only under {@code mondrian.calcite.strict=full}
     */
    public static boolean unsupportedShapeMayFallBack() {
        return !"full".equals(strictValue());
    }

    /**
     * Whether an UNKNOWN Calcite failure (any other RuntimeException or
     * AssertionError) may be served by the legacy SQL generator instead.
     *
     * @return true only under {@code mondrian.calcite.strict=false}
     */
    public static boolean unknownFailureMayFallBack() {
        return "false".equals(strictValue());
    }

    /**
     * Builds the error for a shape that cannot be translated while fallback
     * is forbidden. Names the site and the translator's own message so the
     * gap is actionable rather than mysterious.
     *
     * @param site Where the translation was attempted, e.g. "tuple-read"
     * @param cause The translator's refusal
     * @return exception to throw
     */
    public static RuntimeException noFallback(
        String site, Throwable cause)
    {
        return new mondrian.olap.MondrianException(
            "Calcite backend: no translation for this " + site
            + " request shape, and " + STRICT_PROPERTY + "=full forbids "
            + "falling back to the legacy SQL generator. Either implement "
            + "the shape in the Calcite translator or relax "
            + STRICT_PROPERTY + ". Translator said: "
            + cause.getMessage(),
            cause);
    }
}

// End CalciteFallbackPolicy.java
