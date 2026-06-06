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
package mondrian.lookml.transpile;

import com.google.common.collect.ImmutableMap;

import java.util.Locale;
import java.util.Optional;

/**
 * Translates Looker's built-in {@code value_format_name} named formats (e.g.
 * {@code usd}, {@code percent_2}, {@code decimal_1}, {@code gbp}) to the
 * Mondrian {@code format_string} mask they correspond to (#115).
 *
 * <p>Looker's literal {@code value_format} masks (e.g. {@code "0.00"}) already
 * map straight through; this table covers only the <em>named</em> presets. A
 * named format not in the table is left verbatim (the caller records a DEGRADE
 * note) — the importer never silently invents a mask it cannot prove.
 *
 * <p>The mapping follows Looker's documented named-format definitions: the
 * {@code _N} suffix is the decimal-place count, currency presets prefix the
 * matching symbol, and {@code percent_N} appends {@code %} (Mondrian scales a
 * percent mask by 100, matching Looker).
 */
final class LookerFormats {
  private LookerFormats() {}

  /** Looker named format → Mondrian format-string mask. */
  private static final ImmutableMap<String, String> NAMED =
      ImmutableMap.<String, String>builder()
          // Plain decimals: decimal_N → #,##0[.0..].
          .put("decimal_0", "#,##0")
          .put("decimal_1", "#,##0.0")
          .put("decimal_2", "#,##0.00")
          .put("decimal_3", "#,##0.000")
          .put("decimal_4", "#,##0.0000")
          // US dollars: usd / usd_N.
          .put("usd", "$#,##0.00")
          .put("usd_0", "$#,##0")
          .put("usd_1", "$#,##0.0")
          .put("usd_2", "$#,##0.00")
          // GB pounds: gbp / gbp_N.
          .put("gbp", "£#,##0.00")
          .put("gbp_0", "£#,##0")
          .put("gbp_1", "£#,##0.0")
          .put("gbp_2", "£#,##0.00")
          // Euros: eur / eur_N.
          .put("eur", "€#,##0.00")
          .put("eur_0", "€#,##0")
          .put("eur_1", "€#,##0.0")
          .put("eur_2", "€#,##0.00")
          // Percentages: percent_N (Mondrian scales a % mask by 100).
          .put("percent_0", "0%")
          .put("percent_1", "0.0%")
          .put("percent_2", "0.00%")
          .put("percent_3", "0.000%")
          .put("percent_4", "0.0000%")
          // Identifiers.
          .put("id", "0")
          .build();

  /** The Mondrian mask for a Looker named format, or empty if it is not a known
   * built-in preset (the caller keeps the name verbatim and DEGRADEs). */
  static Optional<String> mondrianMask(String namedFormat) {
    if (namedFormat == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        NAMED.get(namedFormat.trim().toLowerCase(Locale.ROOT)));
  }

  /** Whether {@code namedFormat} is a known Looker built-in preset. */
  static boolean isKnown(String namedFormat) {
    return mondrianMask(namedFormat).isPresent();
  }
}

// End LookerFormats.java
