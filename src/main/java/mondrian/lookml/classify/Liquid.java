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
package mondrian.lookml.classify;

/**
 * Detects Liquid templating in a LookML string value.
 *
 * <p>Only {@code {{ ... }}} (output) and {@code {% ... %}} (tag) markers are
 * Liquid. The {@code ${...}} field-reference syntax is ordinary LookML and is
 * deliberately NOT treated as Liquid.
 */
final class Liquid {
  private Liquid() {}

  private static final String OUTPUT_MARKER = "{{";
  private static final String TAG_MARKER = "{%";

  /** Whether the given text contains Liquid output or tag markers. */
  static boolean isPresent(String text) {
    if (text == null || text.isEmpty()) {
      return false;
    }
    return text.contains(OUTPUT_MARKER) || text.contains(TAG_MARKER);
  }
}

// End Liquid.java
