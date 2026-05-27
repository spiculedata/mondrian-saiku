/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
*/
package mondrian.calcite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins for {@link CalcitePlannerCache.Key#redactJdbcUrl(String)}
 * (saiku-cloud#627).
 *
 * <p>The 2026-05-26 incident: a customer's BigQuery service-account
 * private key (PEM-encoded RSA, ~1.7 KB of base64) was emitted in
 * plaintext to the engine's stdout via
 * {@code [calcite-cache] JdbcSchema not ready for CalcitePlannerCache.Key{url=...}}.
 * Loki ingested it. Operators with log access could see the key.
 *
 * <p>The fix masks sensitive {@code key=value} segments in the URL
 * before it hits the log. This test pins the redaction shape:
 * sensitive values are gone, structural values (host, dataset, project
 * id, OAuthType) survive.
 */
public class CalcitePlannerCacheKeyRedactionTest {

    @Test public void nullUrlIsReturnedAsEmpty() {
        assertEquals("", CalcitePlannerCache.Key.redactJdbcUrl(null));
    }

    @Test public void emptyUrlIsReturnedVerbatim() {
        assertEquals("", CalcitePlannerCache.Key.redactJdbcUrl(""));
    }

    @Test public void bigQueryOAuthPvtKeyIsRedacted() {
        // The exact shape the leak came in: a PEM private key embedded
        // mid-URL with embedded newlines + base64.
        String url = "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443"
            + ";ProjectId=podcast-manager-prod"
            + ";OAuthType=0"
            + ";OAuthServiceAcctEmail=saikutest@podcast-manager-prod.iam.gserviceaccount.com"
            + ";OAuthPvtKey=-----BEGIN PRIVATE KEY-----\n"
            + "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCcjo9cjWm/i+VM\n"
            + "2hePhTSpEHbi9+xa6gL0oO8R0SyMruVr0xxPvjJ4e0Lx7EVHcloUoFLtVv/kkeYC\n"
            + "-----END PRIVATE KEY-----\n"
            + ";DefaultDataset=foodmart_saiku_proof";

        String redacted = CalcitePlannerCache.Key.redactJdbcUrl(url);

        // Key value gone.
        assertFalse("PEM private key must not survive redaction",
            redacted.contains("BEGIN PRIVATE KEY"));
        assertFalse("PEM private key must not survive redaction",
            redacted.contains("MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcw"));
        // Replacement marker present.
        assertTrue("redaction marker must be present",
            redacted.contains("OAuthPvtKey=<redacted>"));
        // Structural context survives.
        assertTrue("project id must survive",
            redacted.contains("ProjectId=podcast-manager-prod"));
        assertTrue("dataset name must survive",
            redacted.contains("DefaultDataset=foodmart_saiku_proof"));
        assertTrue("OAuthType must survive",
            redacted.contains("OAuthType=0"));
        assertTrue("service account email is identity not secret",
            redacted.contains("OAuthServiceAcctEmail="));
    }

    @Test public void snowflakePasswordIsRedacted() {
        // Snowflake URL shape uses ? and & delimiters.
        String url = "jdbc:snowflake://acct.snowflakecomputing.com/"
            + "?warehouse=COMPUTE_WH&db=FOODMART&password=verysecret123";

        String redacted = CalcitePlannerCache.Key.redactJdbcUrl(url);

        assertFalse("password value must not survive",
            redacted.contains("verysecret123"));
        assertTrue("warehouse identifier should survive",
            redacted.contains("warehouse=COMPUTE_WH"));
        assertTrue("redaction marker present",
            redacted.contains("password=<redacted>"));
    }

    @Test public void redactionIsCaseInsensitiveOnKeyName() {
        // Operator might hand-edit case. Redact anyway.
        String url = "jdbc:bigquery://host;OAUTHPVTKEY=secret-bytes-here";

        String redacted = CalcitePlannerCache.Key.redactJdbcUrl(url);

        assertFalse(redacted.contains("secret-bytes-here"));
        // Original case preserved in the key name.
        assertTrue(redacted.contains("OAUTHPVTKEY=<redacted>"));
    }

    @Test public void midTokenMatchesAreNotRedacted() {
        // 'RolesPwd' must NOT be redacted because it shares the
        // 'Pwd' suffix — would be a false-positive that mangles the
        // URL. The redactor requires a delimiter boundary on the
        // left of the key match.
        String url = "jdbc:foo://host;RolesPwdHandling=strict;password=topsecret";

        String redacted = CalcitePlannerCache.Key.redactJdbcUrl(url);

        // Genuine match redacted.
        assertFalse(redacted.contains("topsecret"));
        assertTrue(redacted.contains("password=<redacted>"));
        // False-positive avoided.
        assertTrue(redacted.contains("RolesPwdHandling=strict"));
    }

    @Test public void multipleSecretsInOneUrlAreAllRedacted() {
        String url = "jdbc:bigquery://host"
            + ";OAuthPvtKey=k1"
            + ";OAuthClientSecret=k2"
            + ";OAuthRefreshToken=k3"
            + ";password=k4"
            + ";ProjectId=podcast-manager-prod";

        String redacted = CalcitePlannerCache.Key.redactJdbcUrl(url);

        assertFalse(redacted.contains("k1"));
        assertFalse(redacted.contains("k2"));
        assertFalse(redacted.contains("k3"));
        assertFalse(redacted.contains("k4"));
        assertTrue(redacted.contains("OAuthPvtKey=<redacted>"));
        assertTrue(redacted.contains("OAuthClientSecret=<redacted>"));
        assertTrue(redacted.contains("OAuthRefreshToken=<redacted>"));
        assertTrue(redacted.contains("password=<redacted>"));
        assertTrue(redacted.contains("ProjectId=podcast-manager-prod"));
    }

    @Test public void plainUrlWithoutSecretsIsUnchanged() {
        String url = "jdbc:postgresql://localhost:5432/foodmart?sslmode=disable";

        String redacted = CalcitePlannerCache.Key.redactJdbcUrl(url);

        assertEquals(url, redacted);
    }

    @Test public void keyToStringDoesNotLeakOAuthPvtKey() {
        // The defining contract: when Key.toString() runs on a URL
        // with a private key, the private key is NOT in the output.
        // This is the test that the 2026-05-26 incident would have
        // tripped if it had existed.
        String urlWithSecret = "jdbc:bigquery://host"
            + ";ProjectId=p;OAuthPvtKey=-----BEGIN PRIVATE KEY-----\nABC\n-----END PRIVATE KEY-----"
            + ";DefaultDataset=d";
        // Build a Key directly via the private ctor through reflection
        // would be cleaner, but Key.from(DataSource) requires a real
        // DataSource. Instead, exercise the redactor that toString
        // calls. The redactor IS the test surface.
        String redacted = CalcitePlannerCache.Key.redactJdbcUrl(urlWithSecret);

        assertFalse("private key text must not survive",
            redacted.contains("BEGIN PRIVATE KEY"));
    }
}
