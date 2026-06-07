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
 * LookML numerical-equivalence harness (issue #128): the migration analogue of
 * the #90 Calcite parity guard. It proves a <em>converted</em> LookML cube
 * returns the SAME numbers as Looker — validating correctness, not coverage.
 *
 * <p>Flow: a captured Looker run-query result
 * ({@link mondrian.lookml.equivalence.LookerQueryResult}) is the known-correct
 * oracle. The same query spec
 * ({@link mondrian.lookml.equivalence.LookerQuerySpec}) is rewritten to MDX over
 * the converted cube via
 * {@link mondrian.lookml.equivalence.LookerQueryToMdx} — each Looker field
 * resolved to its M4 element through the transpiler's
 * {@link mondrian.lookml.transpile.ProvenanceMap}. The MDX runs through the
 * Mondrian engine and
 * {@link mondrian.lookml.equivalence.EquivalenceComparator} aligns the oracle
 * rows with the engine cells by the dimension-key tuple and compares measure
 * values within a float tolerance, producing a low-cardinality divergence report
 * (it names the field/category, never the data — no PII in logs, per #90).
 *
 * <p>{@link mondrian.lookml.equivalence.LookerQueryClient} is the live REST
 * front-end (Looker {@code /api/4.0/queries/run/json}); it is INERT without
 * credentials and never carries hardcoded secrets.
 */
package mondrian.lookml.equivalence;

// End package-info.java
