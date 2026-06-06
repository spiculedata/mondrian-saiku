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

/** Handles events that occur while parsing a LookML list.
 *
 * @see ObjectHandler */
public interface ListHandler {
  /** Adds a comment to this list.
   *
   * <p>The default implementation ignores the comment. */
  default ListHandler comment(Pos pos, String comment) {
    return this;
  }

  /** Adds a string to this list.
   *
   * <p>The default implementation ignores the string. */
  default ListHandler string(Pos pos, String value) {
    return this;
  }

  /** Adds a number to this list.
   *
   * <p>The default implementation ignores the number. */
  default ListHandler number(Pos pos, Number value) {
    return this;
  }

  /** Adds an identifier to this list.
   *
   * <p>The default implementation ignores the identifier. */
  default ListHandler identifier(Pos pos, String value) {
    return this;
  }

  /** Adds a string-identifier pair to this list.
   *
   * <p>The default implementation ignores the pair. */
  default ListHandler pair(Pos pos, String ref, String identifier) {
    return this;
  }

  /** Adds an element to this list whose value is a list,
   * and returns the handler for the sub-list.
   *
   * <p>Unlike {@link #list}, this method does not close the sub-list. The
   * caller must remember to call {@link #close} on the returned
   * {@link ListHandler}.
   *
   * <p>The default implementation returns a list-handler that ignores
   * the contents of the list. */
  default ListHandler listOpen(Pos pos) {
    return LaxHandlers.nullListHandler();
  }

  /** Starts and ends a list item whose value is a list.
   *
   * <p>Unlike {@link #listOpen(Pos)}, always returns this
   * {@code ListHandler}. */
  default ListHandler list(Pos pos, Consumer<ListHandler> consumer) {
    ListHandler h = listOpen(pos);
    consumer.accept(h);
    h.close(pos);
    return this;
  }

  /** Finishes this list.
   *
   * <p>The default implementation does nothing. */
  default void close(Pos pos) {
  }
}

// End ListHandler.java
