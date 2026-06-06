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

/**
 * LookML parser for the LookML&rarr;Mondrian-M4 importer (issue #98).
 *
 * <p>Most of this package is vendored from
 * <a href="https://github.com/hydromatic/lookml">hydromatic/lookml</a>
 * (Apache-2.0) and patched; see the NOTICE file. The public entry point is
 * {@link mondrian.lookml.parse.LookmlParser#parse(String)}, which returns an
 * immutable, walkable {@link mondrian.lookml.parse.LookmlNode} document.
 *
 * <p>To walk the AST: a {@code LookmlNode} exposes its properties as an ordered
 * {@code (key, Value)} list; {@link mondrian.lookml.parse.LookmlNode#children}
 * lifts sub-objects into nodes. Scalar values are the concrete
 * {@link mondrian.lookml.parse.Values} shapes (the "value tags") documented on
 * {@link mondrian.lookml.parse.Value}.
 */
package mondrian.lookml.parse;

// End package-info.java
