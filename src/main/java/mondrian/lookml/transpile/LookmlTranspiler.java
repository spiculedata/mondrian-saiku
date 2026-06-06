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

import mondrian.lookml.classify.LookmlClassifier;
import mondrian.lookml.model.Classification;
import mondrian.lookml.model.ClassificationResult;
import mondrian.lookml.model.CoverageRecord;
import mondrian.lookml.model.Scope;
import mondrian.lookml.parse.LookmlNode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Transpiles the CLEAN/DEGRADE subset of a parsed LookML document into a
 * loadable Mondrian-M4 schema (issue #101).
 *
 * <p>Pure: a {@link LookmlNode} (the parsed AST) goes in, a
 * {@link TranspileResult} — the M4 YAML schema, its loadable XML (via the
 * existing {@link mondrian.schema.yaml.m4.M4YamlToXml}) and a
 * {@link ProvenanceMap} — comes out. It first runs the {@link LookmlClassifier}
 * safety gate and emits <em>only</em> the constructs it classifies as CLEAN or
 * DEGRADE; every REFUSE is skipped and nothing is recorded for it.
 *
 * <p>v1 mapping (single-base star/snowflake): explore &rarr; cube; joined views
 * &rarr; conformed dimensions with {@code foreign_key} links; base-view plain
 * dimensions &rarr; degenerate dimensions with a {@code fact} link; sum / count
 * / min / max / avg / count_distinct measures &rarr; M4 measures; equality
 * {@code filters:} (no Liquid) measures &rarr; calculated members. The schema
 * model is built as an ordered map and serialised with the same Jackson YAML
 * settings the M4 converter round-trips, then loaded through that converter.
 */
public final class LookmlTranspiler {

  private static final ObjectMapper YAML;
  static {
    final YAMLFactory f = new YAMLFactory()
        .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER);
    YAML = new ObjectMapper(f);
  }

  private static final String SCHEMA_NAME = "LookML";
  private static final String METAMODEL_VERSION = "4.0";

  /** Transpiles a parsed LookML document. */
  public TranspileResult transpile(LookmlNode document) {
    requireNonNull(document, "document");

    final ClassificationResult classification =
        new LookmlClassifier().classify(document);
    final Eligibility eligible = Eligibility.from(classification);
    final ProvenanceMap.Builder provenance = ProvenanceMap.builder();

    // Index views by name so explores can resolve base / joined views.
    final Map<String, LookmlNode> viewsByName = indexViews(document);

    final M4SchemaModel model = new M4SchemaModel(SCHEMA_NAME,
        METAMODEL_VERSION);
    final CubeEmitter cubeEmitter =
        new CubeEmitter(model, viewsByName, eligible, provenance);

    for (LookmlNode explore : document.children(TranspileKeywords.EXPLORE)) {
      final String exploreName = explore.name().orElse("");
      if (exploreName.isEmpty() || !eligible.explore(exploreName)) {
        continue;
      }
      cubeEmitter.emit(explore);
    }

    final String yaml = dump(model.root());
    return new TranspileResult(yaml, provenance.build(), classification);
  }

  private static Map<String, LookmlNode> indexViews(LookmlNode document) {
    final Map<String, LookmlNode> views = new LinkedHashMap<>();
    for (LookmlNode view : document.children(TranspileKeywords.VIEW)) {
      view.name().ifPresent(n -> views.putIfAbsent(n, view));
    }
    return views;
  }

  private static String dump(Map<String, Object> root) {
    try {
      return YAML.writeValueAsString(root);
    } catch (Exception e) {
      throw new IllegalStateException(
          "failed to serialise transpiled M4 YAML", e);
    }
  }

  /**
   * The CLEAN/DEGRADE-eligible set, derived once from the classification: an
   * explore is eligible if {@code explore:<name>} is not REFUSE; a field is
   * eligible if {@code <view>.<field>} is not REFUSE.
   */
  static final class Eligibility {
    private final Set<String> eligibleQualifiedNames;

    private Eligibility(Set<String> eligibleQualifiedNames) {
      this.eligibleQualifiedNames = eligibleQualifiedNames;
    }

    static Eligibility from(ClassificationResult classification) {
      final Set<String> ok = new HashSet<>();
      addEligible(classification, Classification.CLEAN, ok);
      addEligible(classification, Classification.DEGRADE, ok);
      return new Eligibility(ok);
    }

    private static void addEligible(ClassificationResult classification,
        Classification kind, Set<String> ok) {
      for (CoverageRecord r : classification.withClassification(kind)) {
        ok.add(r.qualifiedName());
      }
    }

    /** Whether {@code explore:<name>} classified CLEAN/DEGRADE. */
    boolean explore(String exploreName) {
      return eligibleQualifiedNames.contains("explore:" + exploreName);
    }

    /** Whether {@code <view>.<field>} classified CLEAN/DEGRADE. */
    boolean field(String viewName, String fieldName) {
      return eligibleQualifiedNames.contains(viewName + "." + fieldName);
    }
  }

  // Re-exported small helpers used by the emitter.

  /** The base view of an explore: {@code from}/{@code view_name} else name. */
  static String baseView(LookmlNode explore) {
    return explore.stringValue(TranspileKeywords.FROM)
        .or(() -> explore.stringValue(TranspileKeywords.VIEW_NAME))
        .orElseGet(() -> explore.name().orElse(""));
  }

  /** The joined view of a join block. */
  static String joinedView(LookmlNode join) {
    return join.stringValue(TranspileKeywords.FROM)
        .or(() -> join.stringValue(TranspileKeywords.VIEW_NAME))
        .orElseGet(() -> join.name().orElse(""));
  }

  /** The physical table backing a view: {@code sql_table_name} (unqualified
   * here; schema-qualified names pass through) else the view name. */
  static String tableOf(LookmlNode view, String viewName) {
    return view.stringValue(TranspileKeywords.SQL_TABLE_NAME)
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .orElse(viewName);
  }

  /** Strips a leading {@code ${TABLE}.} from a dim/measure {@code sql:},
   * returning the bare column, or empty if there is no simple column. */
  static Optional<String> columnFromSql(LookmlNode field) {
    final Optional<String> sql = field.stringValue(TranspileKeywords.SQL);
    if (sql.isEmpty()) {
      return Optional.empty();
    }
    final String s = sql.get().trim();
    final String bare = s.startsWith(TranspileKeywords.TABLE_REF_PREFIX)
        ? s.substring(TranspileKeywords.TABLE_REF_PREFIX.length()).trim()
        : s;
    // Only accept a simple, single-column reference; anything with SQL syntax
    // is left to the field-name fallback (the classifier already cleared it).
    if (bare.matches("[A-Za-z_][\\w]*")) {
      return Optional.of(bare);
    }
    return Optional.empty();
  }

  /** The column a dimension/measure reads: its {@code sql} column else its
   * own name. */
  static String columnOf(LookmlNode field, String fieldName) {
    return columnFromSql(field).orElse(fieldName);
  }

  /** Joins (eligible explore is a star, so these are all star joins). */
  static List<LookmlNode> joins(LookmlNode explore) {
    return explore.children(TranspileKeywords.JOIN);
  }
}

// End LookmlTranspiler.java
