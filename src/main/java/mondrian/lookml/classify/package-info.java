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
 * Static safety gate for the LookML&rarr;Mondrian-M4 importer (issue #100).
 *
 * <p>{@link mondrian.lookml.classify.LookmlClassifier} consumes a parsed
 * {@link mondrian.lookml.parse.LookmlNode} and produces a
 * {@link mondrian.lookml.model.ClassificationResult}: a {@code CLEAN} /
 * {@code DEGRADE} / {@code REFUSE} verdict per explore and per field, with no
 * conversion and no warehouse access. The transpiler (#101) consumes the
 * {@code CLEAN}/{@code DEGRADE} subset; the coverage report (#102) reports on
 * the whole result.
 */
package mondrian.lookml.classify;

// End package-info.java
