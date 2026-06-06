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
 * The CLEAN / DEGRADE / REFUSE contract shared by the LookML&rarr;M4 importer
 * phases (issue #98).
 *
 * <p>These are pure, immutable data types with no dependency on the parser or
 * the engine; they are the seam between the classifier (#100), the transpiler
 * (#101) and the coverage report (#102). The classifier emits a
 * {@link mondrian.lookml.model.ClassificationResult} (a list of
 * {@link mondrian.lookml.model.CoverageRecord}); the transpiler and report
 * consume it.
 */
package mondrian.lookml.model;

// End package-info.java
