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
package mondrian.lookml.model;

/**
 * The LookML construct a {@link CoverageRecord} is about.
 */
public enum Scope {
  /** A LookML project (the whole import). */
  PROJECT,

  /** A {@code model} file. */
  MODEL,

  /** An {@code explore}. */
  EXPLORE,

  /** A {@code view}. */
  VIEW,

  /** A field: a {@code dimension}, {@code dimension_group}, {@code measure}
   * or {@code parameter}. */
  FIELD
}

// End Scope.java
