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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Multi-file LookML resolution / flatten pass (issue #116, part A).
 *
 * <p>Takes a parsed LookML document — typically the concatenation of every
 * {@code .lkml} file discovered under a project root (the
 * {@link mondrian.lookml.report.LookmlReportCli} already builds that file set) —
 * and produces a single RESOLVED document that the existing
 * {@link mondrian.lookml.classify.LookmlClassifier} and
 * {@link mondrian.lookml.transpile.LookmlTranspiler} consume unchanged.
 *
 * <p>What it resolves:
 * <ul>
 *   <li><b>{@code include:}</b> — the file set is merged upstream by the CLI;
 *       any residual {@code include:} statements are dropped from the resolved
 *       document (they have already been satisfied by the merge).</li>
 *   <li><b>{@code extends:}</b> — {@code orders { extends: [base] ... }} copies
 *       {@code base}'s properties then overlays {@code orders}'s own, so the
 *       extending definition wins on conflict.</li>
 *   <li><b>Refinements ({@code +view}/{@code +explore}/{@code +model})</b> —
 *       a refinement {@code +orders { measure: x {...} }} is layered onto the
 *       base {@code orders}: scalar properties override, named sub-objects
 *       (dimension/measure/join/...) merge by name, and an access_filter or
 *       other security-relevant construct introduced by the refinement is
 *       preserved (never dropped or widened).</li>
 *   <li><b>{@code @{constant}}</b> — substituted with the value declared in a
 *       top-level {@code constant: name { value: "..." }} block.</li>
 * </ul>
 *
 * <p>Safety (issue #98): a reference that cannot be resolved — a missing
 * {@code extends} base, a refinement with no base, or an undeclared constant —
 * is RECORDED as a {@link FlattenDiagnostic} and the construct is left
 * as-parsed, never silently dropped or guessed.
 *
 * <p>Immutable: the input document is not mutated; a fresh document is built.
 */
public final class LookmlFlattener {

  /** Top-level object types that participate in extends/refinement layering. */
  private static final ImmutableSet<String> REFINABLE_TYPES =
      ImmutableSet.of("view", "explore", "model");

  /** The {@code include:} key, dropped from the resolved output. */
  private static final String INCLUDE = "include";

  /** The {@code constant:} key holding {@code @{}} substitution values. */
  private static final String CONSTANT = "constant";

  /** The {@code value:} property inside a {@code constant:} block. */
  private static final String VALUE = "value";

  /** The {@code extends:} key (list of base object names). */
  private static final String EXTENDS = "extends";

  /** Flattens a parsed LookML document. */
  public FlattenResult flatten(LookmlNode document) {
    requireNonNull(document, "document");
    final List<FlattenDiagnostic> diagnostics = new ArrayList<>();

    // 1. Collect declared constants for @{} substitution.
    final Map<String, String> constants = collectConstants(document);

    // 2. Resolve each top-level property in order, dropping include:/constant:
    //    and the refinement entries (which are merged into their bases).
    final PairList<String, Value> out = PairList.of();
    final Set<String> emittedRefinable = new LinkedHashSet<>();
    document.properties().forEach((key, value) -> {
      if (INCLUDE.equals(key) || CONSTANT.equals(key)) {
        return; // satisfied by the merge / consumed as a constant table
      }
      if (REFINABLE_TYPES.contains(key)) {
        return; // handled below, grouped by base name
      }
      out.add(key, substitute(value, constants, diagnostics, key));
    });

    // 3. For each refinable type, layer extends + refinements onto bases and
    //    emit the resolved base objects in first-seen order.
    for (String type : REFINABLE_TYPES) {
      emitResolved(type, document, constants, out, emittedRefinable,
          diagnostics);
    }

    final LookmlNode resolved = LookmlNode.of(null, out);
    return new FlattenResult(substituteDoc(resolved, constants, diagnostics),
        diagnostics);
  }

  // --- constants ---------------------------------------------------------

  private static Map<String, String> collectConstants(LookmlNode document) {
    final Map<String, String> constants = new LinkedHashMap<>();
    for (LookmlNode c : document.children(CONSTANT)) {
      c.name().ifPresent(name ->
          c.stringValue(VALUE).ifPresent(v -> constants.put(name, v)));
    }
    return constants;
  }

  // --- extends + refinements --------------------------------------------

  /**
   * Emits the resolved objects of one type. Bases (names without a leading
   * {@code +}) are resolved with their {@code extends:} applied, then every
   * refinement ({@code +name}) is layered on; a refinement with no base yields
   * a {@link FlattenDiagnostic} and is preserved as-is so it is never silently
   * dropped.
   */
  private void emitResolved(String type, LookmlNode document,
      Map<String, String> constants, PairList<String, Value> out,
      Set<String> emitted, List<FlattenDiagnostic> diagnostics) {
    final List<LookmlNode> objects = document.children(type);

    // Index base definitions and group refinements by target name.
    final Map<String, LookmlNode> bases = new LinkedHashMap<>();
    final Map<String, List<LookmlNode>> refinements = new LinkedHashMap<>();
    for (LookmlNode obj : objects) {
      final String name = obj.name().orElse(null);
      if (name == null) {
        continue;
      }
      if (name.startsWith("+")) {
        refinements.computeIfAbsent(name.substring(1),
            k -> new ArrayList<>()).add(obj);
      } else {
        bases.put(name, obj);
      }
    }

    // Emit each base in declaration order, fully resolved.
    for (Map.Entry<String, LookmlNode> e : bases.entrySet()) {
      final String name = e.getKey();
      LookmlNode resolved = applyExtends(type, e.getValue(), bases,
          diagnostics);
      for (LookmlNode refinement : refinements.getOrDefault(name,
          List.of())) {
        resolved = merge(resolved, refinement);
      }
      out.add(type, resolved.toValue());
      emitted.add(name);
    }

    // Any refinement targeting a base we never saw is dangling: record it and
    // preserve it verbatim rather than dropping it.
    refinements.forEach((target, refs) -> {
      if (!bases.containsKey(target)) {
        for (LookmlNode ref : refs) {
          diagnostics.add(FlattenDiagnostic.of(
              FlattenDiagnostic.Kind.DANGLING_REFINEMENT, type,
              "+" + target,
              "refinement +" + target + " has no base " + type
              + " to layer onto; preserved as-parsed"));
          out.add(type, ref.toValue());
        }
      }
    });
  }

  /** Applies {@code extends:} to a base object: a referenced base's properties
   * are copied first, then this object's own properties overlay them. */
  private LookmlNode applyExtends(String type, LookmlNode obj,
      Map<String, LookmlNode> bases, List<FlattenDiagnostic> diagnostics) {
    final List<String> baseNames = extendsList(obj);
    if (baseNames.isEmpty()) {
      return obj;
    }
    LookmlNode acc = null;
    for (String baseName : baseNames) {
      final LookmlNode base = bases.get(baseName);
      if (base == null) {
        diagnostics.add(FlattenDiagnostic.of(
            FlattenDiagnostic.Kind.MISSING_EXTENDS_BASE, type, baseName,
            type + " " + obj.name().orElse("?") + " extends missing base "
            + baseName));
        continue;
      }
      // Recursively resolve the base's own extends first.
      final LookmlNode resolvedBase = applyExtends(type, base, bases,
          diagnostics);
      acc = acc == null ? resolvedBase : merge(acc, resolvedBase);
    }
    // Drop the extends: key, then overlay this object's own properties. The
    // result keeps the EXTENDING object's name, not the base's.
    final LookmlNode self = withoutKey(obj, EXTENDS);
    if (acc == null) {
      return self;
    }
    return merge(acc, self).renameTo(obj.name().orElse(null));
  }

  private static List<String> extendsList(LookmlNode obj) {
    final List<String> names = new ArrayList<>();
    obj.value(EXTENDS).ifPresent(v -> {
      if (v instanceof Values.ListValue) {
        for (ValueImpl item : ((Values.ListValue) v).list) {
          names.add(LookmlNode.asString(item));
        }
      } else {
        names.add(LookmlNode.asString(v));
      }
    });
    return names;
  }

  // --- merge -------------------------------------------------------------

  /**
   * Layers {@code overlay} onto {@code base}, returning a new node. Scalar
   * properties from the overlay override the base; named sub-objects (those
   * whose value is a {@link Values.NamedObjectValue}, e.g. dimension/measure/
   * join) are merged by name; repeated unnamed/list keys present in the overlay
   * replace those in the base. The merge is order-preserving: base keys keep
   * their position, overlay-only keys are appended.
   */
  private LookmlNode merge(LookmlNode base, LookmlNode overlay) {
    // Bucket the base properties so we can merge same-key/same-name children.
    final PairList<String, Value> out = PairList.of();

    // Index overlay named sub-objects by (key,name) and overlay scalar keys.
    final Map<String, LookmlNode> overlayNamed = new LinkedHashMap<>();
    final Set<String> overlayScalarKeys = new LinkedHashSet<>();
    final Set<String> consumed = new LinkedHashSet<>();
    overlay.properties().forEach((k, v) -> {
      final String childName = namedObjectName(v);
      if (childName != null) {
        overlayNamed.put(k + " " + childName, asNode(k, v));
      } else {
        overlayScalarKeys.add(k);
      }
    });

    // Walk base properties, applying overlay overrides in place.
    base.properties().forEach((k, v) -> {
      final String childName = namedObjectName(v);
      if (childName != null) {
        final String idx = k + " " + childName;
        final LookmlNode ov = overlayNamed.get(idx);
        if (ov != null) {
          out.add(k, merge(asNode(k, v), ov).toValue());
          consumed.add(idx);
        } else {
          out.add(k, v);
        }
      } else if (overlayScalarKeys.contains(k)) {
        // Overlay overrides this scalar/list key: skip base, emit overlay once.
        if (!consumed.contains("scalar " + k)) {
          for (Value ov : overlay.values(k)) {
            out.add(k, ov);
          }
          consumed.add("scalar " + k);
        }
      } else {
        out.add(k, v);
      }
    });

    // Append overlay-only properties (named children and scalars) in order.
    overlay.properties().forEach((k, v) -> {
      final String childName = namedObjectName(v);
      if (childName != null) {
        if (!consumed.contains(k + " " + childName)) {
          out.add(k, v);
        }
      } else if (!consumed.contains("scalar " + k)) {
        out.add(k, v);
        consumed.add("scalar " + k);
      }
    });

    return LookmlNode.of(base.name().orElse(overlay.name().orElse(null)), out);
  }

  // --- @{} substitution --------------------------------------------------

  private LookmlNode substituteDoc(LookmlNode doc,
      Map<String, String> constants, List<FlattenDiagnostic> diagnostics) {
    final PairList<String, Value> out = PairList.of();
    doc.properties().forEach((k, v) ->
        out.add(k, substitute(v, constants, diagnostics, k)));
    return LookmlNode.of(doc.name().orElse(null), out);
  }

  /** Substitutes {@code @{name}} occurrences inside scalar/code/identifier
   * values and recurses into objects and lists. */
  private Value substitute(Value v, Map<String, String> constants,
      List<FlattenDiagnostic> diagnostics, String key) {
    if (v instanceof Values.StringValue) {
      return Values.string(subst(((Values.StringValue) v).s, constants,
          diagnostics, key));
    }
    if (v instanceof Values.CodeValue) {
      return Values.code(subst(((Values.CodeValue) v).s, constants,
          diagnostics, key));
    }
    if (v instanceof Values.IdentifierValue) {
      final String id = ((Values.IdentifierValue) v).id;
      return id.contains("@{")
          ? Values.identifier(subst(id, constants, diagnostics, key))
          : v;
    }
    if (v instanceof Values.NamedObjectValue) {
      final Values.NamedObjectValue n = (Values.NamedObjectValue) v;
      final LookmlNode node = asNode(key, v);
      return substituteDoc(node, constants, diagnostics)
          .renameTo(n.name).toValue();
    }
    if (v instanceof Values.ObjectValue) {
      final LookmlNode node = asNode(key, v);
      return substituteDoc(node, constants, diagnostics).toValue();
    }
    if (v instanceof Values.ListValue) {
      final List<ValueImpl> items = new ArrayList<>();
      for (ValueImpl item : ((Values.ListValue) v).list) {
        items.add((ValueImpl) substitute(item, constants, diagnostics, key));
      }
      return Values.list(items);
    }
    return v;
  }

  private static String subst(String s, Map<String, String> constants,
      List<FlattenDiagnostic> diagnostics, String key) {
    if (s == null || !s.contains("@{")) {
      return s;
    }
    final StringBuilder b = new StringBuilder();
    int i = 0;
    while (i < s.length()) {
      final int start = s.indexOf("@{", i);
      if (start < 0) {
        b.append(s, i, s.length());
        break;
      }
      final int end = s.indexOf('}', start + 2);
      if (end < 0) {
        b.append(s, i, s.length());
        break;
      }
      b.append(s, i, start);
      final String name = s.substring(start + 2, end);
      if (constants.containsKey(name)) {
        b.append(constants.get(name));
      } else {
        diagnostics.add(FlattenDiagnostic.of(
            FlattenDiagnostic.Kind.UNDECLARED_CONSTANT, key, "@{" + name + "}",
            "undeclared constant @{" + name + "}; left as-parsed"));
        b.append("@{").append(name).append('}');
      }
      i = end + 1;
    }
    return b.toString();
  }

  // --- helpers -----------------------------------------------------------

  private static String namedObjectName(Value v) {
    return v instanceof Values.NamedObjectValue
        ? ((Values.NamedObjectValue) v).name
        : null;
  }

  private static LookmlNode asNode(String key, Value v) {
    return new LookmlNode(null, PairList.of(key, v)).child(key).orElseThrow();
  }

  private static LookmlNode withoutKey(LookmlNode node, String dropKey) {
    final PairList<String, Value> out = PairList.of();
    node.properties().forEach((k, v) -> {
      if (!k.equals(dropKey)) {
        out.add(k, v);
      }
    });
    return LookmlNode.of(node.name().orElse(null), out);
  }
}

// End LookmlFlattener.java
