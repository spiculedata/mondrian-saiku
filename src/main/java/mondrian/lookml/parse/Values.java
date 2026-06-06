/*
 * Licensed to the LookML Authors under one or more contributor
 * license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.
 * The LookML Authors license this file to you under the Apache
 * License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License.  You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
//
// Vendored from net.hydromatic:lookml (https://github.com/hydromatic/lookml),
// Apache-2.0. Re-packaged into mondrian.lookml.parse for the LookML->M4
// importer (issue #98). Adapted: removed the LookmlWriter "write" methods (the
// writer layer was dropped) and promoted the concrete value classes and their
// fields to public so that the parse result is a walkable, immutable AST.
//
package mondrian.lookml.parse;

import mondrian.lookml.parse.util.ImmutablePairList;
import mondrian.lookml.parse.util.PairList;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * Factory and concrete implementations of {@link ValueImpl}.
 *
 * <p>These are the "value tags" that appear in the parsed AST. To walk the
 * tree, {@code instanceof}-match on the public nested classes
 * (e.g. {@link NamedObjectValue}, {@link ListValue}, {@link CodeValue}).
 */
public class Values {
  private Values() {}

  /** Iterates over a list of number values. */
  public static void forEachNumber(Object o, Consumer<Number> consumer) {
    @SuppressWarnings("unchecked")
    final List<NumberValue> list = (List<NumberValue>) o;
    list.forEach(value -> consumer.accept(value.number));
  }

  /** Iterates over a list of string values. */
  public static void forEachString(Object o, Consumer<String> consumer) {
    @SuppressWarnings("unchecked")
    final List<StringValue> list = (List<StringValue>) o;
    list.forEach(value -> consumer.accept(value.s));
  }

  /** Iterates over a list of identifier values. */
  public static void forEachIdentifier(Object o, Consumer<String> consumer) {
    @SuppressWarnings("unchecked")
    final List<IdentifierValue> list = (List<IdentifierValue>) o;
    list.forEach(value -> consumer.accept(value.id));
  }

  /** Iterates over a list of string-pair values. */
  public static void forEachStringPair(Object o,
      BiConsumer<String, String> consumer) {
    @SuppressWarnings("unchecked")
    final List<PairValue> list = (List<PairValue>) o;
    list.forEach(value -> consumer.accept(value.ref, value.s));
  }

  static NumberValue number(Number value) {
    return new NumberValue(value);
  }

  static IdentifierValue identifier(String value) {
    return new IdentifierValue(value);
  }

  static StringValue string(String value) {
    return new StringValue(value);
  }

  static CodeValue code(String value) {
    return new CodeValue(value);
  }

  static ListValue list(List<ValueImpl> list) {
    return new ListValue(list);
  }

  static NamedObjectValue namedObject(String name,
      PairList<String, ValueImpl> properties) {
    return new NamedObjectValue(name, properties);
  }

  static ObjectValue object(PairList<String, ValueImpl> properties) {
    return new ObjectValue(properties);
  }

  static PairValue pair(String ref, String identifier) {
    return new PairValue(ref, identifier);
  }

  /** Value of a property or list element whose value is an identifier.
   *
   * <p>Covers bare identifiers, enum values, booleans ({@code yes}/{@code no}),
   * refinement references ({@code +orders}) and dotted references
   * ({@code customers.id}). */
  public static class IdentifierValue extends ValueImpl {
    public final String id;

    IdentifierValue(String id) {
      this.id = id;
    }

    @Override public String toString() {
      return id;
    }
  }

  /** Value of a property or list element whose value is a number. */
  public static class NumberValue extends ValueImpl {
    public final Number number;

    NumberValue(Number number) {
      this.number = number;
    }

    /** Returns the value as a {@link BigDecimal}, the type the parser
     * produces. */
    public BigDecimal toBigDecimal() {
      return number instanceof BigDecimal
          ? (BigDecimal) number
          : new BigDecimal(number.toString());
    }

    @Override public String toString() {
      return String.valueOf(number);
    }
  }

  /** Value of a property or list element whose value is a string. */
  public static class StringValue extends ValueImpl {
    public final String s;

    StringValue(String s) {
      this.s = s;
    }

    @Override public String toString() {
      return s;
    }
  }

  /** Value of a property whose value is a {@code ;;}-terminated code block,
   * e.g. {@code sql}, {@code sql_on}, {@code html}.
   *
   * <p>Liquid templating ({@code {% %\}}, {@code {{ }\}}, {@code ${...\}}) is
   * preserved verbatim. */
  public static class CodeValue extends ValueImpl {
    public final String s;

    CodeValue(String s) {
      this.s = s;
    }

    @Override public String toString() {
      return s;
    }
  }

  /** Value of a {@code ref: "string"} pair in a list,
   * e.g. {@code filters: [created_date: "30 days"]}. */
  public static class PairValue extends ValueImpl {
    public final String ref;
    public final String s;

    PairValue(String ref, String s) {
      this.ref = ref;
      this.s = s;
    }

    @Override public String toString() {
      return ref + ": " + s;
    }
  }

  /** Value of a property or list element whose value is a list. */
  public static class ListValue extends ValueImpl {
    public final List<ValueImpl> list;

    ListValue(List<ValueImpl> list) {
      this.list = list;
    }

    @Override public String toString() {
      return list.toString();
    }
  }

  /** Value of a property whose value is an object.
   *
   * <p>For example,
   * <blockquote><pre>{@code
   * conditionally_filter: {
   *   filters: [f3: "> 10"]
   *   unless: [f1, f2]
   * }
   * }</pre></blockquote>
   *
   * <p>The name of the property, {@code conditionally_filter}, will be held
   * in the enclosing property list. */
  public static class ObjectValue extends ValueImpl {
    public final ImmutablePairList<String, ValueImpl> properties;

    ObjectValue(PairList<String, ValueImpl> properties) {
      this.properties = ImmutablePairList.copyOf(properties);
    }

    @Override public String toString() {
      return properties.toString();
    }
  }

  /** Value of a property whose value is an object and that also has a name.
   *
   * <p>For example,
   * <blockquote><pre>{@code
   * dimension: d1 {
   *   sql: orderDate;;
   *   type: int
   * }
   * }</pre></blockquote>
   *
   * <p>{@link #name} is "d1", and {@link #properties} has entries for "sql" and
   * "type". The name of the property, {@code dimension}, is held in the
   * enclosing property list. */
  public static class NamedObjectValue extends ObjectValue {
    public final String name;

    NamedObjectValue(String name, PairList<String, ValueImpl> properties) {
      super(properties);
      this.name = requireNonNull(name);
    }

    @Override public String toString() {
      return name + " " + super.toString();
    }
  }
}

// End Values.java
