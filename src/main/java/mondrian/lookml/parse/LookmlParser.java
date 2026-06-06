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
package mondrian.lookml.parse;

import mondrian.lookml.parse.util.PairList;

import com.google.common.collect.ImmutableSet;

import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * Public entry point for parsing LookML into an immutable, walkable AST.
 *
 * <p>This is the seam the LookML&rarr;Mondrian-M4 importer (issue #98) builds
 * on. It wraps the vendored push parser ({@link LookmlParsers}) and returns the
 * document as a {@link LookmlNode}.
 *
 * <p>Example:
 * <blockquote><pre>{@code
 * LookmlNode doc = LookmlParser.parse(lookmlText);
 * for (LookmlNode view : doc.children("view")) {
 *   System.out.println(view.name());
 * }
 * }</pre></blockquote>
 */
public final class LookmlParser {
  private LookmlParser() {}

  /**
   * LookML keys whose value is a {@code ;;}-terminated code block.
   *
   * <p>The lexer switches to a verbatim code state on seeing these keys, so
   * Liquid templating ({@code {% %}}, {@code {{ }}}, {@code ${...}}) and SQL
   * are preserved byte-for-byte. Covers the code-valued keys across LookML
   * dimensions, measures, joins, derived tables and HTML formatters.
   */
  public static final ImmutableSet<String> CODE_PROPERTY_NAMES =
      ImmutableSet.of(
          "sql",
          "sql_on",
          "sql_always_on",
          "sql_always_where",
          "sql_where",
          "sql_table_name",
          "sql_start",
          "sql_end",
          "sql_latitude",
          "sql_longitude",
          "sql_distinct_key",
          "sql_trigger",
          "sql_trigger_value",
          "sql_foreign_key",
          "sql_step",
          "expression_custom_filter",
          "html");
  // NOTE (issue #98, finding #4): "default_value" was previously listed here,
  // but in LookML it is a STRING-valued key (parameters/filters), never a
  // ;;-terminated code block. Treating it as code made the IN_CODE lexer run
  // past the unterminated quoted string and swallow subsequent properties up to
  // the next ;;, which surfaced far downstream as a spurious <EOF> parse error.
  // A sweep of the validation corpus found 90 default_value occurrences, none
  // with ;;. See LookmlParserTest#defaultValueIsStringNotCodeAndDoesNotSwallow*.

  /** Default tab size assumed when computing column positions. */
  private static final int DEFAULT_TAB_SIZE = 1;

  /**
   * Parses LookML text into a document node.
   *
   * @param lookml LookML source text (a {@code .lkml}, {@code .model.lkml} or
   *               {@code .view.lkml} file's contents)
   * @return the document root; its {@link LookmlNode#children(String)} are the
   *         top-level objects (e.g. all {@code view:} / {@code explore:}
   *         blocks)
   * @throws LookmlParseException if the input is not well-formed LookML
   */
  public static LookmlNode parse(String lookml) {
    requireNonNull(lookml, "lookml");
    return parse(Sources.fromString(lookml));
  }

  /**
   * Parses LookML from a {@link Source} into a document node.
   *
   * @param source source of LookML text
   * @return the document root
   * @throws LookmlParseException if the input is not well-formed LookML
   */
  public static LookmlNode parse(Source source) {
    requireNonNull(source, "source");
    final LookmlParsers.Config config =
        LookmlParsers.config()
            .withSource(source)
            .withTabSize(DEFAULT_TAB_SIZE)
            .withCodePropertyNames(CODE_PROPERTY_NAMES);

    final AtomicReference<PairList<String, Value>> ref =
        new AtomicReference<>();
    final ObjectHandler handler = LaxHandlers.build(ref::set);
    LookmlParsers.parse(handler, config);

    final PairList<String, Value> top = ref.get();
    if (top == null) {
      // The builder always fires onClose, but guard defensively.
      throw new LookmlParseException(
          "no LookML document produced for source " + source);
    }
    return new LookmlNode(null, top);
  }
}

// End LookmlParser.java
