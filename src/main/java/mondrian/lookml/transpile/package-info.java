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
 * LookML&rarr;Mondrian-M4 transpiler (issue #101): the clean-port path.
 *
 * <p>{@link mondrian.lookml.transpile.LookmlTranspiler#transpile} takes a
 * parsed {@link mondrian.lookml.parse.LookmlNode}, runs the
 * {@link mondrian.lookml.classify.LookmlClassifier} safety gate, and emits a
 * loadable M4 schema for the CLEAN/DEGRADE subset only, plus a
 * {@link mondrian.lookml.transpile.ProvenanceMap} linking each LookML construct
 * to the M4 element it produced. The schema is built as an ordered YAML map and
 * loaded through the existing
 * {@link mondrian.schema.yaml.m4.M4YamlToXml} converter — no XML is hand-rolled.
 */
package mondrian.lookml.transpile;

// End package-info.java
