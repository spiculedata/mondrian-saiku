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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Scanner;

import static java.util.Objects.requireNonNull;

/**
 * Issue #128: the REST implementation of {@link LookerQueryClient} against
 * Looker API 4.0. Uses only the JDK ({@link HttpURLConnection}) so it adds no
 * heavy SDK dependency.
 *
 * <p><b>Unverified here:</b> there is no live Looker instance in the build
 * environment, so this path is exercised only structurally — the offline
 * fixture harness is the proven part of #128. The flow is:
 * <ol>
 *   <li>{@code POST /api/4.0/login} with {@code client_id}/{@code client_secret}
 *       &rarr; an {@code access_token} (obtained lazily, on first {@link #run});
 *   <li>{@code POST /api/4.0/queries/run/json} with the inline query body
 *       (model/view/fields/filters) &rarr; a JSON array of result rows, parsed
 *       by {@link LookerQueryResult#fromJson(String)}.
 * </ol>
 *
 * <p>The constructor stores the base URL and credentials but opens NO
 * connection — the object is inert until {@link #run} is called. Credentials
 * are never logged.
 */
final class LookerRestQueryClient implements LookerQueryClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int TIMEOUT_MS = 30_000;

  private final String baseUrl;
  private final String clientId;
  private final String clientSecret;

  LookerRestQueryClient(String baseUrl, String clientId, String clientSecret) {
    this.baseUrl = stripTrailingSlash(requireNonNull(baseUrl, "baseUrl"));
    this.clientId = requireNonNull(clientId, "clientId");
    this.clientSecret = requireNonNull(clientSecret, "clientSecret");
  }

  @Override public LookerQueryResult run(LookerQuerySpec spec) {
    requireNonNull(spec, "spec");
    final String token = login();
    final String body = buildRunBody(spec);
    final String json = post(
        baseUrl + "/api/4.0/queries/run/json", body, token);
    return LookerQueryResult.fromJson(json);
  }

  /** Exchanges client credentials for a short-lived access token. */
  private String login() {
    final String form = "client_id=" + urlEncode(clientId)
        + "&client_secret=" + urlEncode(clientSecret);
    final String json = postForm(baseUrl + "/api/4.0/login", form);
    try {
      final JsonNode node = MAPPER.readTree(json);
      final JsonNode token = node.get("access_token");
      if (token == null || token.asText().isEmpty()) {
        throw new IllegalStateException("Looker login returned no access_token");
      }
      return token.asText();
    } catch (IOException e) {
      throw new UncheckedIOException("invalid Looker login response", e);
    }
  }

  /** Builds the inline run-query body (model/view/fields/filters). */
  private String buildRunBody(LookerQuerySpec spec) {
    final ObjectNode body = MAPPER.createObjectNode();
    if (spec.model() != null) {
      body.put("model", spec.model());
    }
    body.put("view", spec.explore());
    final ArrayNode fields = body.putArray("fields");
    for (String f : spec.allFields()) {
      fields.add(f);
    }
    if (!spec.filters().isEmpty()) {
      final ObjectNode filters = body.putObject("filters");
      for (Map.Entry<String, String> e : spec.filters().entrySet()) {
        filters.put(e.getKey(), e.getValue());
      }
    }
    try {
      return MAPPER.writeValueAsString(body);
    } catch (IOException e) {
      throw new UncheckedIOException("could not serialise run-query body", e);
    }
  }

  private String post(String url, String jsonBody, String token) {
    return send(url, "POST", jsonBody, "application/json", token);
  }

  private String postForm(String url, String form) {
    return send(url, "POST", form, "application/x-www-form-urlencoded", null);
  }

  private String send(String urlStr, String method, String body,
      String contentType, String token) {
    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) new URL(urlStr).openConnection();
      conn.setRequestMethod(method);
      conn.setConnectTimeout(TIMEOUT_MS);
      conn.setReadTimeout(TIMEOUT_MS);
      conn.setRequestProperty("Content-Type", contentType);
      if (token != null) {
        conn.setRequestProperty("Authorization", "token " + token);
      }
      conn.setDoOutput(true);
      try (OutputStream os = conn.getOutputStream()) {
        os.write(body.getBytes(StandardCharsets.UTF_8));
      }
      final int status = conn.getResponseCode();
      if (status < 200 || status >= 300) {
        // Value-free: report the endpoint + status, never the response body
        // (which may echo data), per #90.
        throw new IllegalStateException(
            "Looker request to " + safeEndpoint(urlStr) + " failed: HTTP "
                + status);
      }
      return readAll(conn.getInputStream());
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Looker request to " + safeEndpoint(urlStr) + " failed", e);
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  private static String readAll(InputStream in) {
    try (Scanner s = new Scanner(in, StandardCharsets.UTF_8.name())) {
      s.useDelimiter("\\A");
      return s.hasNext() ? s.next() : "";
    }
  }

  private static String urlEncode(String s) {
    try {
      return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.name());
    } catch (IOException e) {
      throw new UncheckedIOException("url-encode failed", e);
    }
  }

  /** The path portion of a URL (drops query/host detail from error messages). */
  private static String safeEndpoint(String url) {
    final int scheme = url.indexOf("://");
    if (scheme < 0) {
      return url;
    }
    final int path = url.indexOf('/', scheme + 3);
    return path < 0 ? url : url.substring(path);
  }

  private static String stripTrailingSlash(String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}

// End LookerRestQueryClient.java
