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

import mondrian.lookml.parse.util.ImmutablePairList;
import mondrian.lookml.parse.util.PairList;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable, walkable wrapper over a parsed LookML object.
 *
 * <p>A {@code LookmlNode} represents one LookML object: a document, a named
 * block ({@code view: orders { ... }}) or an anonymous block
 * ({@code derived_table: { ... }}). It exposes the object's properties as an
 * ordered {@link PairList} of (key, {@link Value}) and offers convenience
 * lookups. A LookML key may legitimately repeat (e.g. several
 * {@code dimension:} entries in a view), so lookups that can return many use
 * {@link #children(String)} / {@link #values(String)}.
 *
 * <p>The {@link Value} of a property is one of the concrete shapes documented
 * on {@link Value} ("value tags"). To descend into a sub-object, use
 * {@link #child(String)} / {@link #children(String)} which lift
 * {@link Values.ObjectValue} / {@link Values.NamedObjectValue} into a
 * {@code LookmlNode}.
 */
public final class LookmlNode {
  /** Name of a named object (e.g. "orders" in {@code view: orders {}}), or null
   * for the document root and anonymous objects. */
  private final String name;
  private final ImmutablePairList<String, Value> properties;

  LookmlNode(String name, PairList<String, Value> properties) {
    this.name = name;
    this.properties = ImmutablePairList.copyOf(properties);
  }

  /** Package-private factory used by {@link LookmlFlattener} to build resolved
   * nodes. The name may be null (document root / anonymous object). */
  static LookmlNode of(String name, PairList<String, Value> properties) {
    return new LookmlNode(name, properties);
  }

  /** Returns a copy of this node with the given name (used during {@code @{}}
   * substitution to preserve a named sub-object's name). */
  LookmlNode renameTo(String newName) {
    final PairList<String, Value> copy = PairList.of();
    properties.forEach((k, v) -> copy.add(k, v));
    return new LookmlNode(newName, copy);
  }

  /** Wraps this node back into a {@link Value} so it can be re-inserted into a
   * parent's property list during flattening. Named nodes become a
   * {@link Values.NamedObjectValue}; anonymous nodes a
   * {@link Values.ObjectValue}. */
  Value toValue() {
    final PairList<String, ValueImpl> impl = narrow(properties);
    return name == null
        ? Values.object(impl)
        : Values.namedObject(name, impl);
  }

  /** Narrows a {@code PairList<String, Value>} back to the {@code ValueImpl}
   * shape the {@link Values} factories require. Every {@link Value} produced by
   * the parser or by flattening is a {@link ValueImpl}. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static PairList<String, ValueImpl> narrow(
      PairList<String, Value> in) {
    final PairList<String, ValueImpl> out = PairList.of();
    in.forEach((k, v) -> out.add(k, (ValueImpl) v));
    return out;
  }

  /** Returns the name of this named object, or empty for the root / anonymous
   * objects. */
  public Optional<String> name() {
    return Optional.ofNullable(name);
  }

  /** Returns the ordered list of (key, value) properties of this object. */
  public ImmutablePairList<String, Value> properties() {
    return properties;
  }

  /** Returns the value of the first property with the given key, or empty. */
  public Optional<Value> value(String key) {
    requireNonNull(key, "key");
    for (int i = 0; i < properties.size(); i++) {
      if (properties.left(i).equals(key)) {
        return Optional.of(properties.right(i));
      }
    }
    return Optional.empty();
  }

  /** Returns the values of every property with the given key, in order. */
  public List<Value> values(String key) {
    requireNonNull(key, "key");
    final ImmutableList.Builder<Value> b = ImmutableList.builder();
    for (int i = 0; i < properties.size(); i++) {
      if (properties.left(i).equals(key)) {
        b.add(properties.right(i));
      }
    }
    return b.build();
  }

  /** Returns the string value of a property, whether it was a quoted string,
   * an identifier, a number or a code block. Empty if absent. */
  public Optional<String> stringValue(String key) {
    return value(key).map(LookmlNode::asString);
  }

  /** Returns the first child object with the given key as a node, lifting
   * {@link Values.ObjectValue} / {@link Values.NamedObjectValue}. Empty if the
   * property is absent or is not an object. */
  public Optional<LookmlNode> child(String key) {
    return value(key).flatMap(v -> toNode(key, v));
  }

  /** Returns every child object with the given key as nodes (e.g. all
   * {@code dimension:} blocks in a view). */
  public List<LookmlNode> children(String key) {
    final ImmutableList.Builder<LookmlNode> b = ImmutableList.builder();
    for (Value v : values(key)) {
      toNode(key, v).ifPresent(b::add);
    }
    return b.build();
  }

  private static Optional<LookmlNode> toNode(String key, Value v) {
    if (v instanceof Values.NamedObjectValue) {
      final Values.NamedObjectValue n = (Values.NamedObjectValue) v;
      return Optional.of(new LookmlNode(n.name, widen(n.properties)));
    }
    if (v instanceof Values.ObjectValue) {
      final Values.ObjectValue o = (Values.ObjectValue) v;
      return Optional.of(new LookmlNode(null, widen(o.properties)));
    }
    return Optional.empty();
  }

  /** A {@code PairList<String, ValueImpl>} is, by construction, also a list of
   * {@code (String, Value)}; widen it without copying element-by-element. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static PairList<String, Value> widen(
      PairList<String, ValueImpl> in) {
    final PairList<String, Value> out = PairList.of();
    in.forEach((k, v) -> out.add(k, v));
    return out;
  }

  /** Renders a {@link Value} as a plain string for the common scalar shapes. */
  public static String asString(Value v) {
    if (v instanceof Values.StringValue) {
      return ((Values.StringValue) v).s;
    }
    if (v instanceof Values.IdentifierValue) {
      return ((Values.IdentifierValue) v).id;
    }
    if (v instanceof Values.CodeValue) {
      return ((Values.CodeValue) v).s;
    }
    if (v instanceof Values.NumberValue) {
      return ((Values.NumberValue) v).toString();
    }
    return v.toString();
  }

  @Override public String toString() {
    final StringBuilder b = new StringBuilder();
    if (name != null) {
      b.append(name).append(' ');
    }
    b.append('{');
    final List<String> parts = new ArrayList<>();
    properties.forEach((k, v) -> parts.add(k + ": " + v));
    b.append(String.join(", ", parts));
    return b.append('}').toString();
  }
}

// End LookmlNode.java
