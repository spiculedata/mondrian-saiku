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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #128: the live {@link LookerQueryClient} stays INERT without
 * credentials — the credentials gate returns empty (no network, no client) and
 * the require-variant throws a clear, value-free error. With all three settings
 * present a client is constructed but STILL opens no connection until
 * {@code run} (the live path is unverified here — there is no Looker instance).
 */
public class LookerQueryClientTest {

  @AfterEach
  public void clearProps() {
    System.clearProperty(LookerQueryClient.ENV_BASE_URL);
    System.clearProperty(LookerQueryClient.ENV_CLIENT_ID);
    System.clearProperty(LookerQueryClient.ENV_CLIENT_SECRET);
  }

  @Test
  public void inertWhenNoCredentials() {
    // Guard: only assert the empty case when the ambient environment also lacks
    // the vars (CI never sets them; a dev machine with them set is skipped).
    if (LookerQueryClient.setting(LookerQueryClient.ENV_BASE_URL).isPresent()) {
      return;
    }
    final Optional<LookerQueryClient> client =
        LookerQueryClient.fromEnvironment();
    assertFalse(client.isPresent(), "no creds → no client, no network");
  }

  @Test
  public void requireThrowsClearErrorWhenNoCredentials() {
    if (LookerQueryClient.setting(LookerQueryClient.ENV_BASE_URL).isPresent()) {
      return;
    }
    final IllegalStateException ex = assertThrows(IllegalStateException.class,
        LookerQueryClient::requireFromEnvironment);
    assertTrue(ex.getMessage().contains("no Looker credentials configured"),
        ex.getMessage());
    // The error names the env vars, never any secret value.
    assertTrue(ex.getMessage().contains(LookerQueryClient.ENV_CLIENT_SECRET));
  }

  @Test
  public void buildsClientWhenAllSettingsPresentButOpensNoConnection() {
    System.setProperty(LookerQueryClient.ENV_BASE_URL, "https://example.test");
    System.setProperty(LookerQueryClient.ENV_CLIENT_ID, "id");
    System.setProperty(LookerQueryClient.ENV_CLIENT_SECRET, "secret");
    final Optional<LookerQueryClient> client =
        LookerQueryClient.fromEnvironment();
    assertTrue(client.isPresent(), "all settings present → client constructed");
    // Constructing does NOT connect: no assertion on run() (no live instance).
  }
}

// End LookerQueryClientTest.java
