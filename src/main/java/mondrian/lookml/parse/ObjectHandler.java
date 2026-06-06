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
// importer (issue #98). Trimmed: dropped schema/writer/logging layers; kept the
// generic push parser and the document builder.
//
package mondrian.lookml.parse;

import java.util.function.Consumer;

/** Handles events that occur while parsing a LookML object.
 *
 * <p>An {@code ObjectHandler} handles a sequence of property values
 * for a document or a section of a document.
 *
 * <p>General-purpose handlers (e.g. those created by
 * {@link LaxHandlers#build(Consumer)}) will typically be for a
 * whole document; the methods {@link #objOpen(Pos, String)} or
 * {@link #objOpen(Pos, String, String)} create handlers for a sub-object.
 * In either case, you must remember to call {@link #close(Pos)} on the
 * handler; the implementation may be relying on this call to
 * complete the life-cycle.
 *
 * <p>Due to the structure of the LookML language, a valid document
 * will contain precisely one property value, which is a named object
 * (and hence a call to {@link #objOpen(Pos, String, String)}).
 * In contrast, a section of a document may contain any number of
 * property values.
 *
 * <p>Methods have defaults, so that it is easy to create a handler
 * that does just what you need it to.
 *
 * @see LaxHandlers#nullObjectHandler()
 * @see ListHandler */
public interface ObjectHandler {

  /** Adds a comment to this object.
   *
   * <p>For example, parsing '{@code # A comment<newline>}'
   * results in a call {@code comment("# comment")}.
   *
   * <p>The default implementation ignores the comment. */
  default ObjectHandler comment(Pos pos, String comment) {
    return this;
  }

  /** Adds a numeric property.
   *
   * <p>For example, parsing '{@code retry_count: 10}'
   * results in a call {@code number("retry_count", 10)}.
   *
   * <p>The default implementation ignores the property. */
  default ObjectHandler number(Pos pos, String propertyName, Number value) {
    return this;
  }

  /** Adds a string property.
   *
   * <p>For example, parsing '{@code city: "San Francisco"}'
   * results in a call {@code string("city", "San Francisco")}.
   *
   * <p>The default implementation ignores the property. */
  default ObjectHandler string(Pos pos, String propertyName, String value) {
    return this;
  }

  /** Adds an identifier (unquoted string) property.
   *
   * <p>Values of enumerated types, such as "true" and "false",
   * "yes" and "no", "asc" and "desc" will use this method.
   * Enumerated types come into play at schema validation.
   *
   * <p>For example, parsing '{@code unique_key: true}'
   * results in a call {@code identifier("unique_key", "true")}.
   *
   * <p>The default implementation ignores the property.  */
  default ObjectHandler identifier(Pos pos, String propertyName, String value) {
    return this;
  }

  /** Adds a code property.
   *
   * <p>For example, parsing '{@code sql: select * from orders;;}'
   * results in a call {@code code("sql", " select * from orders")}.
   *
   * <p>The default implementation ignores the property. */
  default ObjectHandler code(Pos pos, String propertyName, String value) {
    return this;
  }

  /** Starts a property whose value is a list.
   *
   * <p>Unlike {@link #list(Pos, String, Consumer)},
   * this method does not close the list: the caller
   * remember to call {@link ListHandler#close}
   * on the returned {@link ListHandler}.
   *
   * <p>The default implementation returns a list-handler that ignores
   * the contents of the list. */
  default ListHandler listOpen(Pos pos, String propertyName) {
    return LaxHandlers.nullListHandler();
  }

  /** Starts and ends a property whose value is a list. */
  default ObjectHandler list(Pos pos, String propertyName,
      Consumer<ListHandler> consumer) {
    ListHandler h = listOpen(pos, propertyName);
    consumer.accept(h);
    h.close(pos);
    return this;
  }

  /** Starts a property whose value is an object, and returns the handler
   * for the sub-object.
   *
   * <p>Unlike {@link #obj(Pos, String, Consumer)},
   * this method does not close the object: the caller
   * must remember to call {@link #close(Pos)}.
   *
   * <p>The default implementation ignores the property,
   * and returns this handler to handle the properties of the sub-object,
   * which are received as if they are at the same nesting level. */
  default ObjectHandler objOpen(Pos pos, String propertyName) {
    return this;
  }

  /** Starts and ends a property whose value is an object, calling a given
   * consumer to allow the user to provide the contents of the object. */
  default ObjectHandler obj(Pos pos, String propertyName,
      Consumer<ObjectHandler> consumer) {
    final ObjectHandler h = objOpen(pos, propertyName);
    consumer.accept(h);
    h.close(pos);
    return this;
  }

  /** Starts a property whose value is a named object, and returns the handler
   * for the sub-object.
   *
   * <p>Unlike {@link #obj(Pos, String, String, Consumer)}
   * and {@link #obj(Pos, String, Consumer)},
   * this method does not close the object: the caller
   * must remember to call {@link #close}.
   *
   * <p>The default implementation ignores the property and its name,
   * and returns this handler to handle the properties of the sub-object,
   * which are received as if they are at the same nesting level. */
  default ObjectHandler objOpen(Pos pos, String propertyName, String name) {
    return this;
  }

  /** Starts and ends a property whose value is a named object, calling a given
   * consumer to allow the user to provide the contents of the object.
   *
   * <p>Unlike {@link #objOpen(Pos, String, String)}
   * and {@link #objOpen(Pos, String)},
   * always returns this {@code ObjectHandler}. */
  default ObjectHandler obj(Pos pos, String propertyName, String name,
      Consumer<ObjectHandler> consumer) {
    final ObjectHandler h = objOpen(pos, propertyName, name);
    consumer.accept(h);
    h.close(pos);
    return this;
  }

  /** Closes this handler.
   *
   * <p>As the implementor of code that drives an ObjectHandler,
   * you must remember to call this method.
   *
   * <p>The default implementation does nothing. */
  default void close(Pos pos) {
  }
}

// End ObjectHandler.java
