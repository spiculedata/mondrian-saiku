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
package mondrian.lookml.equivalence;

import java.util.Optional;

/**
 * Issue #128: the live Looker query front-end — runs a {@link LookerQuerySpec}
 * as an inline query against a real Looker instance via
 * {@code /api/4.0/queries/run/json} and returns the {@link LookerQueryResult}
 * oracle.
 *
 * <p>This is the ONLY part of the harness that touches the network, and it is
 * deliberately fenced off:
 * <ul>
 *   <li>It is an INTERFACE — the offline harness (transpile &rarr; MDX &rarr;
 *       compare) never depends on a live instance and is fully proven with
 *       captured fixtures.</li>
 *   <li>{@link #fromEnvironment()} is the credentials gate: it reads
 *       {@code LOOKER_BASE_URL}, {@code LOOKER_CLIENT_ID} and
 *       {@code LOOKER_CLIENT_SECRET} from system properties (falling back to
 *       environment variables) and returns {@link Optional#empty()} when any is
 *       missing. NOTHING connects at construction — no network, no secrets in
 *       code.</li>
 * </ul>
 *
 * <p>The REST implementation is {@link LookerRestQueryClient}.
 */
public interface LookerQueryClient {

  /** System-property / environment key for the Looker base URL. */
  String ENV_BASE_URL = "LOOKER_BASE_URL";
  /** System-property / environment key for the Looker API client id. */
  String ENV_CLIENT_ID = "LOOKER_CLIENT_ID";
  /** System-property / environment key for the Looker API client secret. */
  String ENV_CLIENT_SECRET = "LOOKER_CLIENT_SECRET";

  /**
   * Runs {@code spec} as an inline query and returns the result. Implementations
   * authenticate lazily on first call (never at construction). Throws on a
   * transport/auth failure with a value-free message.
   */
  LookerQueryResult run(LookerQuerySpec spec);

  /**
   * The credentials gate. Returns a live client only when all three settings
   * are present (checked as system properties first, then environment
   * variables); otherwise empty — the harness then stays purely offline.
   *
   * <p>No connection is opened here: the returned client connects only when
   * {@link #run} is first invoked.
   */
  static Optional<LookerQueryClient> fromEnvironment() {
    final Optional<String> baseUrl = setting(ENV_BASE_URL);
    final Optional<String> clientId = setting(ENV_CLIENT_ID);
    final Optional<String> clientSecret = setting(ENV_CLIENT_SECRET);
    if (baseUrl.isPresent() && clientId.isPresent()
        && clientSecret.isPresent()) {
      return Optional.of(new LookerRestQueryClient(
          baseUrl.get(), clientId.get(), clientSecret.get()));
    }
    return Optional.empty();
  }

  /**
   * Like {@link #fromEnvironment()} but throws a clear, value-free error when
   * credentials are absent — for call sites that REQUIRE a live instance.
   */
  static LookerQueryClient requireFromEnvironment() {
    return fromEnvironment().orElseThrow(() -> new IllegalStateException(
        "no Looker credentials configured: set " + ENV_BASE_URL + ", "
            + ENV_CLIENT_ID + " and " + ENV_CLIENT_SECRET
            + " (system properties or environment variables)"));
  }

  /** Reads a setting from a system property, falling back to an env var; a
   * blank value counts as absent. Never logs the value. */
  static Optional<String> setting(String key) {
    String v = System.getProperty(key);
    if (v == null || v.trim().isEmpty()) {
      v = System.getenv(key);
    }
    if (v == null || v.trim().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(v.trim());
  }
}

// End LookerQueryClient.java
