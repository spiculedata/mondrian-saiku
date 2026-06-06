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
// importer (issue #98). Trimmed to the document-builder path only: the
// writer / logger / tee / filter / schema-validation handlers were dropped
// (they depended on the LookmlWriter and LookmlSchema layers which we do not
// vendor). The ObjectBuilder / ListBuilder logic is unchanged.
//
package mondrian.lookml.parse;

import mondrian.lookml.parse.util.PairList;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Implementations of {@link ObjectHandler} that build an immutable AST. */
public class LaxHandlers {
  private LaxHandlers() {}

  /** Creates a list handler that swallows all events. */
  public static ListHandler nullListHandler() {
    return NullListHandler.INSTANCE;
  }

  /** Creates an object handler that swallows all events. */
  public static ObjectHandler nullObjectHandler() {
    return NullObjectHandler.INSTANCE;
  }

  /** Creates an ObjectHandler that converts events into a document.
   *
   * <p>On {@link ObjectHandler#close(Pos)} the supplied consumer is invoked
   * with the completed top-level property list. Each value is one of the
   * concrete {@link Values} shapes. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static ObjectHandler build(
      Consumer<PairList<String, Value>> consumer) {
    return new ObjectBuilder(
        (Consumer<PairList<String, ValueImpl>>) (Consumer) consumer);
  }

  /** Implementation of {@link ObjectHandler}
   * that builds a list of properties,
   * then calls a consumer on the completed list. */
  static class ObjectBuilder implements ObjectHandler {
    final PairList<String, ValueImpl> properties = PairList.of();
    final Consumer<PairList<String, ValueImpl>> onClose;

    ObjectBuilder(Consumer<PairList<String, ValueImpl>> onClose) {
      this.onClose = onClose;
    }

    @Override public ObjectBuilder comment(Pos pos, String comment) {
      // ignore comment
      return this;
    }

    @Override public ObjectBuilder number(Pos pos, String propertyName,
        Number value) {
      properties.add(propertyName, Values.number(value));
      return this;
    }

    @Override public ObjectBuilder string(Pos pos, String propertyName,
        String value) {
      properties.add(propertyName, Values.string(value));
      return this;
    }

    @Override public ObjectBuilder identifier(Pos pos, String propertyName,
        String value) {
      properties.add(propertyName, Values.identifier(value));
      return this;
    }

    @Override public ObjectBuilder code(Pos pos, String propertyName,
        String value) {
      properties.add(propertyName, Values.code(value));
      return this;
    }

    @Override public ListBuilder listOpen(Pos pos, String propertyName) {
      return new ListBuilder(list ->
          properties.add(propertyName, Values.list(list)));
    }

    @Override public ObjectHandler objOpen(Pos pos, String propertyName,
        String name) {
      return new ObjectBuilder(properties ->
          this.properties.add(propertyName,
              Values.namedObject(name, properties)));
    }

    @Override public ObjectHandler objOpen(Pos pos, String propertyName) {
      return new ObjectBuilder(properties ->
          this.properties.add(propertyName,
              Values.object(properties)));
    }

    @Override public void close(Pos pos) {
      onClose.accept(properties);
    }
  }

  /** Implementation of {@link ListHandler}
   * that builds a list of values,
   * then calls a consumer when done. */
  static class ListBuilder implements ListHandler {
    final Consumer<List<ValueImpl>> onClose;
    final List<ValueImpl> list = new ArrayList<>();

    ListBuilder(Consumer<List<ValueImpl>> onClose) {
      this.onClose = onClose;
    }

    @Override public ListHandler string(Pos pos, String value) {
      list.add(Values.string(value));
      return this;
    }

    @Override public ListHandler number(Pos pos, Number value) {
      list.add(Values.number(value));
      return this;
    }

    @Override public ListHandler identifier(Pos pos, String value) {
      list.add(Values.identifier(value));
      return this;
    }

    @Override public ListHandler pair(Pos pos, String ref, String identifier) {
      list.add(Values.pair(ref, identifier));
      return this;
    }

    @Override public ListHandler comment(Pos pos, String comment) {
      // Ignore the comment
      return this;
    }

    @Override public ListHandler listOpen(Pos pos) {
      return new ListBuilder(list -> this.list.add(Values.list(list)));
    }

    @Override public void close(Pos pos) {
      onClose.accept(list);
    }
  }

  /** Implementation of {@link ListHandler}
   * that discards all events. */
  enum NullListHandler implements ListHandler {
    INSTANCE
  }

  /** Implementation of {@link ObjectHandler}
   * that discards all events. */
  enum NullObjectHandler implements ObjectHandler {
    INSTANCE
  }
}

// End LaxHandlers.java
