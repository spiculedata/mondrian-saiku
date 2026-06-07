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
 * Looker SDK Explore-JSON front-end for the LookML importer (issue #116,
 * part B). Maps a pre-resolved {@code LookmlModelExplore} JSON document (Looker
 * API output) into the same {@link mondrian.lookml.parse.LookmlNode} AST the
 * text parser produces, so the classifier / transpiler / report pipeline is
 * reused unchanged.
 */
package mondrian.lookml.json;

// End package-info.java
