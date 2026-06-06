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
// importer (issue #98). Adapted: the original Value was an opaque marker whose
// only implementations carried a LookmlWriter "write" method. We dropped the
// writer layer, so the concrete subclasses (see Values) expose their data
// directly, making the parse result a walkable, immutable AST.
//
package mondrian.lookml.parse;

import java.util.function.Consumer;

/**
 * A LookML value held in a property or a list.
 *
 * <p>Every {@code Value} produced by the parser is one of the concrete
 * subclasses declared in {@link Values}; switch / {@code instanceof} on those
 * types to walk the tree. The available shapes (the "value tags") are:
 *
 * <ul>
 *   <li>{@link Values.NumberValue} &mdash; a numeric literal;
 *   <li>{@link Values.StringValue} &mdash; a double-quoted string;
 *   <li>{@link Values.IdentifierValue} &mdash; a bare identifier, enum value,
 *       boolean ({@code yes}/{@code no}), refinement ref ({@code +orders}) or
 *       dotted ref ({@code customers.id});
 *   <li>{@link Values.CodeValue} &mdash; a {@code ;;}-terminated code block
 *       (e.g. {@code sql}/{@code sql_on}/{@code html}); Liquid is preserved
 *       verbatim;
 *   <li>{@link Values.PairValue} &mdash; a {@code ref: "string"} pair inside a
 *       list (e.g. {@code filters: [created_date: "30 days"]});
 *   <li>{@link Values.ListValue} &mdash; a bracketed list of values;
 *   <li>{@link Values.ObjectValue} &mdash; an anonymous {@code { ... }} block;
 *   <li>{@link Values.NamedObjectValue} &mdash; a named {@code name { ... }}
 *       block (e.g. {@code view: orders { ... }}).
 * </ul>
 *
 * @see Values
 * @see LaxHandlers#build(Consumer)
 */
public interface Value {
}

// End Value.java
