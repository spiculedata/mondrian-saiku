/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mondrian.lookml.classify;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static recognition of the <em>bounded</em>, enumerable LookML Liquid patterns
 * the importer can route to a shipped feature (#118), as distinct from arbitrary
 * computed Liquid that must stay {@code REFUSE_LIQUID}.
 *
 * <p>This is <strong>not</strong> a Liquid interpreter. It matches a closed set
 * of known usages with regexes and otherwise classifies the fragment as
 * arbitrary. The bounded value always resolves through the #105 query-context
 * sandbox (typed + enumerated, bind-only), preserving the no-injection guarantee.
 *
 * <ol>
 *   <li>{@code {{ _user_attributes['x'] }}} / {@code {{ _user_attributes._x }}}
 *   &rarr; a {@code session.x} query parameter ({@link Kind#USER_ATTRIBUTE}).</li>
 *   <li>{@code {% parameter X %}} / {@code {% parameter X %}} use of a declared
 *   bounded parameter ({@link Kind#PARAMETER}).</li>
 *   <li>{@code {% condition Y %}} &hellip; {@code {% endcondition %}}
 *   ({@link Kind#CONDITION}).</li>
 * </ol>
 *
 * <p>Anything containing control flow that computes SQL ({@code if}/{@code
 * elsif}/{@code unless}/{@code for}/{@code assign}/{@code capture}), or a
 * {@code {{ ... }}} output that is not a single user-attribute reference, is
 * {@link Kind#ARBITRARY}.
 */
final class LiquidPattern {
  private LiquidPattern() {}

  /** Which bounded mapping a Liquid fragment routes to, or ARBITRARY. */
  enum Kind {
    /** {@code {{ _user_attributes['x'] }}} &rarr; session.x parameter / grant. */
    USER_ATTRIBUTE,
    /** {@code {% parameter X %}} use of a declared bounded parameter. */
    PARAMETER,
    /** {@code {% condition Y %}}…{% endcondition %} parameter-bound filter. */
    CONDITION,
    /** Computed Liquid: stays REFUSE_LIQUID. */
    ARBITRARY
  }

  // --- arbitrary control-flow tags (compute SQL → never routable) ---------
  private static final Pattern CONTROL_FLOW_TAG = Pattern.compile(
      "\\{%-?\\s*(if|elsif|else|endif|unless|endunless|for|endfor|assign"
          + "|capture|endcapture|case|when|endcase|cycle|increment|decrement"
          + "|include|tablename|date_start|date_end)\\b",
      Pattern.CASE_INSENSITIVE);

  // --- bounded {% parameter X %} / {% condition Y %} tags -----------------
  private static final Pattern PARAMETER_TAG = Pattern.compile(
      "\\{%-?\\s*parameter\\s+([A-Za-z_][\\w.]*)\\s*-?%\\}",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern CONDITION_TAG = Pattern.compile(
      "\\{%-?\\s*condition\\s+([A-Za-z_][\\w.]*)\\s*-?%\\}",
      Pattern.CASE_INSENSITIVE);

  // --- bounded {{ _user_attributes['x'] }} / {{ _user_attributes._x }} ----
  // A single output expression referencing only the user-attributes object.
  private static final Pattern USER_ATTRIBUTE_OUTPUT = Pattern.compile(
      "\\{\\{-?\\s*_user_attributes\\s*(?:\\[\\s*['\"]([\\w.-]+)['\"]\\s*\\]"
          + "|\\.([\\w]+))\\s*-?\\}\\}",
      Pattern.CASE_INSENSITIVE);

  // Any {{ ... }} output marker — used to reject arbitrary output expressions.
  private static final Pattern OUTPUT_EXPR = Pattern.compile(
      "\\{\\{-?(.*?)-?\\}\\}", Pattern.DOTALL);

  /**
   * Classifies a single Liquid-bearing string into the bounded mapping it
   * matches, or {@link Kind#ARBITRARY}. The caller has already confirmed the
   * string contains Liquid markers.
   */
  static Kind classify(String text) {
    if (text == null) {
      return Kind.ARBITRARY;
    }
    // 1. Any computed control-flow tag is arbitrary, full stop.
    if (CONTROL_FLOW_TAG.matcher(text).find()) {
      return Kind.ARBITRARY;
    }
    // 2. Every {{ ... }} output must be a bare _user_attributes reference; any
    //    other output (arithmetic, field._value, string-building) is arbitrary.
    if (hasNonUserAttributeOutput(text)) {
      return Kind.ARBITRARY;
    }
    // 3. Route by the (now-known-safe) tag / output present.
    if (CONDITION_TAG.matcher(text).find()) {
      return Kind.CONDITION;
    }
    if (PARAMETER_TAG.matcher(text).find()) {
      return Kind.PARAMETER;
    }
    if (USER_ATTRIBUTE_OUTPUT.matcher(text).find()) {
      return Kind.USER_ATTRIBUTE;
    }
    // Liquid present but no recognised bounded form (e.g. a lone {% ... %} we
    // do not map, or a {%- raw -%} block): refuse.
    return Kind.ARBITRARY;
  }

  /** The user-attribute name a {{ _user_attributes[...] }} output references, or
   * empty if {@code text} is not a single such reference. */
  static java.util.Optional<String> userAttributeName(String text) {
    if (text == null) {
      return java.util.Optional.empty();
    }
    final Matcher m = USER_ATTRIBUTE_OUTPUT.matcher(text);
    if (!m.find()) {
      return java.util.Optional.empty();
    }
    final String bracket = m.group(1);
    return java.util.Optional.of(bracket != null ? bracket : m.group(2));
  }

  /** The parameter / field a {% parameter X %} or {% condition Y %} tag binds,
   * or empty. Returns the leaf name (after any {@code view.}). */
  static java.util.Optional<String> boundFieldName(String text) {
    if (text == null) {
      return java.util.Optional.empty();
    }
    Matcher m = CONDITION_TAG.matcher(text);
    if (m.find()) {
      return java.util.Optional.of(leaf(m.group(1)));
    }
    m = PARAMETER_TAG.matcher(text);
    if (m.find()) {
      return java.util.Optional.of(leaf(m.group(1)));
    }
    return java.util.Optional.empty();
  }

  /** Whether any {{ ... }} output in the text is something other than a single
   * bare {@code _user_attributes} reference (then the whole field is arbitrary). */
  private static boolean hasNonUserAttributeOutput(String text) {
    final Matcher out = OUTPUT_EXPR.matcher(text);
    while (out.find()) {
      final String whole = out.group();
      if (!USER_ATTRIBUTE_OUTPUT.matcher(whole).matches()) {
        return true;
      }
    }
    return false;
  }

  private static String leaf(String ref) {
    final int dot = ref.lastIndexOf('.');
    return dot >= 0 ? ref.substring(dot + 1) : ref;
  }
}

// End LiquidPattern.java
