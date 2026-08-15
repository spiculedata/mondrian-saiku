/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule / Saiku community
// All Rights Reserved.
*/
package mondrian.property;

import dev.hegel.Generator;
import java.util.List;

/**
 * Shared generators for the property suite.
 *
 * <p>Two kinds of string generator live here, and picking the wrong one is the most common way to
 * write a property test that looks thorough but proves nothing:
 *
 * <ul>
 *   <li>{@link #adversarialText()} — biased hard towards the characters that break quoting and
 *       parsing ({@code ] [ ' " ; = . &}, whitespace, control characters). Use it for anything
 *       whose contract is "survives being embedded in a larger syntax". Unbiased Unicode almost
 *       never produces a bracket, so a naive {@code text()} generator would explore the escaping
 *       code path roughly never and report green forever.
 *   <li>{@link #identifierText()} — plain names with no syntax characters at all. Use it where the
 *       test is about structure rather than escaping, so a failure means the structural logic is
 *       wrong rather than the escaping.
 * </ul>
 */
final class Generators {

    private Generators() {}

    /**
     * The characters that actually break Mondrian's string handling: MDX identifier brackets and
     * the key marker, SQL and MDX string delimiters, the connect-string separators, and the
     * segment separator.
     */
    private static final String SYNTAX_CHARS = "][';\"=.&";

    /**
     * Text biased towards syntax-breaking characters, for round-trip and escaping properties.
     *
     * <p>The bias is the point. A generator is only as good as the code paths it reaches, and every
     * interesting escaping bug in this area needs a delimiter character to show up at all.
     */
    static Generator<String> adversarialText() {
        return dev.hegel.Generators.oneOf(
                // Pure syntax soup: short strings drawn only from the dangerous set. These are the
                // shrink targets — a minimal counterexample here reads as "]]" rather than as a
                // paragraph of Cyrillic with one bracket buried in it.
                dev.hegel.Generators.text().includeCharacters(SYNTAX_CHARS).maxSize(8),
                // Realistic values with syntax characters mixed in, which is what a hostile or
                // merely unlucky schema/connect string actually looks like.
                dev.hegel.Generators.text().includeCharacters(SYNTAX_CHARS).maxSize(30),
                // Unbiased text, to keep Unicode, whitespace and control characters in play.
                dev.hegel.Generators.text().maxSize(20));
    }

    /** As {@link #adversarialText()} but never empty, for positions where empty is not legal. */
    static Generator<String> nonEmptyAdversarialText() {
        return adversarialText().filter(s -> !s.isEmpty());
    }

    /**
     * Plain identifier-ish names: letters and digits only, never empty. No syntax characters, so a
     * failure in a test using these is a structural failure rather than an escaping one.
     */
    static Generator<String> identifierText() {
        return dev.hegel.Generators.text()
                .categories("Lu", "Ll", "Nd")
                .minSize(1)
                .maxSize(12);
    }

    /**
     * Names that are distinct ignoring case.
     *
     * <p>Needed because several Mondrian maps ({@code Util.PropertyList}, schema element lookup)
     * match keys case-insensitively, so a generated pair of {@code "a"} and {@code "A"} collides
     * and the test would be asserting on a collision rather than on the behaviour under test.
     */
    static Generator<List<String>> distinctIgnoringCase(int maxSize) {
        return dev.hegel.Generators.lists(identifierText())
                .maxSize(maxSize)
                .filter(names -> names.stream()
                                .map(n -> n.toUpperCase(java.util.Locale.ROOT))
                                .distinct()
                                .count()
                        == names.size());
    }
}
