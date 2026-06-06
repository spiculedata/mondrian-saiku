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
 * The three exit codes the LookML&rarr;M4 validating transpiler assigns to each
 * construct (issue #98).
 *
 * <p>The guiding principle: a converted model that returns plausible-but-wrong
 * numbers is the worst outcome, so a construct that cannot be safely converted
 * is refused loudly rather than emitted.
 */
public enum Classification {
  /** Emit M4 schema; full fidelity. */
  CLEAN,

  /** Emit M4 schema, but a capability is lost; a warning is recorded. */
  DEGRADE,

  /** Emit nothing; record a precise diagnostic. */
  REFUSE
}

// End Classification.java
