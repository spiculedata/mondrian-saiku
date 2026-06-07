package mondrian.calcite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public enum MondrianBackend {
    LEGACY, CALCITE;

    public static final String PROPERTY = "mondrian.backend";

    private static final Logger LOGGER =
        LoggerFactory.getLogger(MondrianBackend.class);

    /** Distinct unrecognized values already warned about, so a hot-path call to
     *  {@link #current()} logs at most once per typo rather than on every read. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    public static MondrianBackend current() {
        String raw = System.getProperty(PROPERTY, "calcite");
        MondrianBackend parsed = parse(raw);
        if (parsed != null) {
            return parsed;
        }
        // A typo'd/unrecognized value must NOT be swallowed silently: it would
        // ignore the operator's intent and select LEGACY, where row-security-
        // secured (PredicateGrant / bridge MemberGrant) loads fail closed. Warn
        // once per distinct bad value so the misconfiguration is visible.
        if (WARNED.add(raw == null ? "" : raw.toLowerCase(Locale.ROOT))) {
            LOGGER.warn(
                "Unrecognized {} value '{}'; falling back to LEGACY. Valid "
                + "values: legacy, calcite. Note: PredicateGrant / bridge "
                + "MemberGrant row-security loads require the Calcite backend "
                + "and will fail closed under LEGACY.",
                PROPERTY, raw);
        }
        return LEGACY;
    }

    /** Parses a raw backend value to its enum constant, or {@code null} if it is
     *  not a recognized value. Pure and side-effect free (no logging) so it is
     *  trivially testable; {@link #current()} layers the warn-once policy on top.
     */
    static MondrianBackend parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return MondrianBackend.valueOf(
                raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public boolean isCalcite() { return this == CALCITE; }

    /** Convenience: {@code current() == CALCITE}. */
    public static boolean isCurrentCalcite() {
        return current() == CALCITE;
    }
}
