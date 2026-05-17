/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.observability;

import mondrian.olap.Connection;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.test.TestContext;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the OpenTelemetry instrumentation foundation wired in
 * {@code RolapConnection.execute(Execution)} for ticket #33 actually
 * emits a {@code mondrian.mdx.execute} span with the expected
 * attributes when an OTel SDK is registered.
 *
 * <p>Confirms three things:
 *
 * <ol>
 *   <li><strong>The span emits.</strong> The instrumentation isn't
 *       silently dropped — running an MDX query against Mondrian
 *       with an in-memory SDK registered produces exactly one
 *       {@code mondrian.mdx.execute} span.</li>
 *   <li><strong>Attributes attach.</strong> The span carries the
 *       schema, cube, and execution-id attributes from {@code
 *       RolapConnection.execute} so downstream dashboards can group/
 *       filter on them.</li>
 *   <li><strong>Errors surface.</strong> When a query throws, the span
 *       records the exception and sets {@code StatusCode.ERROR} —
 *       trace viewers turn that into a red flag instead of silent
 *       success.</li>
 * </ol>
 *
 * <p>Uses the OTel SDK's {@link InMemorySpanExporter} — no network,
 * no environment dependency, no extra config required. Test resets
 * the global SDK between runs so other tests in the same JVM aren't
 * affected.
 */
public class MdxExecuteSpanTest {

    private InMemorySpanExporter exporter;
    private OpenTelemetrySdk sdk;

    @Before
    public void registerInMemorySdk() {
        // Reset any previously-registered global; OTel forbids
        // re-registration without a reset.
        GlobalOpenTelemetry.resetForTest();
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider tp = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
        sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tp)
            .build();
        GlobalOpenTelemetry.set(sdk);
    }

    @After
    public void tearDown() {
        if (sdk != null) {
            sdk.close();
        }
        GlobalOpenTelemetry.resetForTest();
    }

    /**
     * Happy-path: an MDX query against FoodMart emits exactly one
     * {@code mondrian.mdx.execute} span with schema + cube attributes.
     *
     * <p>Note on filtering: Mondrian's schema initialisation parses
     * calculated members defined in XML, each of which goes through
     * {@code ConnectionBase.parseStatement} → emits its own parse /
     * validate / compile spans. Our assertion filters for the
     * mdx.execute span specifically since that one only fires per
     * user-issued query.
     */
    @Test
    public void successfulQueryEmitsMdxExecuteSpanWithAttributes() {
        TestContext ctx = TestContext.instance();
        Connection conn = ctx.getConnection();
        try {
            Query q = conn.parseQuery(
                "SELECT [Measures].[Unit Sales] ON COLUMNS FROM [Sales]");
            Result result = conn.execute(q);
            assertNotNull(result);
            result.close();
        } finally {
            conn.close();
        }

        SpanData executeSpan = null;
        int executeCount = 0;
        for (SpanData s : exporter.getFinishedSpanItems()) {
            if ("mondrian.mdx.execute".equals(s.getName())) {
                executeSpan = s;
                executeCount++;
            }
        }
        assertEquals(
            "expected exactly one mondrian.mdx.execute span",
            1, executeCount);
        assertEquals(
            "default span status should be UNSET on success",
            StatusCode.UNSET, executeSpan.getStatus().getStatusCode());

        // Attribute presence (values vary by test fixture); these are
        // the keys the dashboards group by.
        assertEquals(
            "FoodMart", executeSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey(
                    "mondrian.schema")));
        assertEquals(
            "Sales", executeSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey(
                    "mondrian.cube")));
        assertNotNull(
            "execution-id attribute should be set",
            executeSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.longKey(
                    "mondrian.execution.id")));
    }

    /**
     * Session 2: a parsed-and-executed query emits the full span family
     * — parse, validate, compile, execute. Validate and compile nest
     * inside parse because Query's constructor calls resolve() inline.
     */
    @Test
    public void parseAndExecuteEmitsFullSpanFamily() {
        TestContext ctx = TestContext.instance();
        Connection conn = ctx.getConnection();
        try {
            Query q = conn.parseQuery(
                "SELECT [Measures].[Unit Sales] ON COLUMNS FROM [Sales]");
            Result result = conn.execute(q);
            result.close();
        } finally {
            conn.close();
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();

        java.util.Map<String, Long> byName = new java.util.HashMap<>();
        for (SpanData s : spans) {
            byName.merge(s.getName(), 1L, Long::sum);
        }

        // Schema init parses calc-members so we see more than just our
        // query's parse/validate/compile spans. Assert AT LEAST one of
        // each.
        assertTrue(
            "expected at least one mondrian.mdx.parse span — got map: "
                + byName,
            byName.getOrDefault("mondrian.mdx.parse", 0L) >= 1);
        assertTrue(
            "expected at least one mondrian.mdx.validate span — got map: "
                + byName,
            byName.getOrDefault("mondrian.mdx.validate", 0L) >= 1);
        assertTrue(
            "expected at least one mondrian.mdx.compile span — got map: "
                + byName,
            byName.getOrDefault("mondrian.mdx.compile", 0L) >= 1);
        // mdx.execute is per-user-query so still exactly one
        assertEquals(
            "expected exactly one mondrian.mdx.execute span — got map: "
                + byName,
            Long.valueOf(1L), byName.get("mondrian.mdx.execute"));
    }

    /**
     * The parse span's {@code mondrian.mdx.length} attribute reflects
     * the input MDX text length — useful for "are big queries slow?"
     * dashboards.
     */
    @Test
    public void parseSpanCarriesMdxLengthAttribute() {
        TestContext ctx = TestContext.instance();
        Connection conn = ctx.getConnection();
        String mdx =
            "SELECT [Measures].[Unit Sales] ON COLUMNS FROM [Sales]";
        try {
            conn.parseQuery(mdx);
        } finally {
            conn.close();
        }

        // Find OUR parse span specifically — schema-init also parses
        // calc-members, each emitting its own parse span. Filter by
        // matching mondrian.mdx.length to the input length.
        SpanData parseSpan = null;
        for (SpanData s : exporter.getFinishedSpanItems()) {
            if ("mondrian.mdx.parse".equals(s.getName())) {
                Long len = s.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.longKey(
                        "mondrian.mdx.length"));
                if (len != null && len == (long) mdx.length()) {
                    parseSpan = s;
                    break;
                }
            }
        }
        assertNotNull(
            "expected a mondrian.mdx.parse span with mdx.length="
                + mdx.length(),
            parseSpan);
    }

    /**
     * Session 3: a cold-cache query emits {@code mondrian.cache.segment.load}
     * + {@code mondrian.sql.execute} spans. The {@code sql.execute} span
     * with {@code mondrian.sql.kind=segment-load} proves the kind
     * attribute is populated correctly.
     */
    @Test
    public void coldCacheQueryEmitsSegmentLoadAndSqlExecuteSpans() {
        boolean savedDisableCaching =
            mondrian.olap.MondrianProperties.instance().DisableCaching.get();
        mondrian.olap.MondrianProperties.instance()
            .DisableCaching.set(true);
        try {
            TestContext ctx = TestContext.instance();
            Connection conn = ctx.getConnection();
            try {
                Query q = conn.parseQuery(
                    "SELECT [Measures].[Unit Sales] ON COLUMNS, "
                    + "[Store].[USA].Children ON ROWS FROM [Sales]");
                Result result = conn.execute(q);
                result.close();
            } finally {
                conn.close();
            }

            java.util.List<SpanData> sqlExecuteSpans =
                new java.util.ArrayList<>();
            java.util.List<SpanData> segmentLoadSpans =
                new java.util.ArrayList<>();
            for (SpanData s : exporter.getFinishedSpanItems()) {
                if ("mondrian.sql.execute".equals(s.getName())) {
                    sqlExecuteSpans.add(s);
                } else if ("mondrian.cache.segment.load".equals(s.getName())) {
                    segmentLoadSpans.add(s);
                }
            }

            assertTrue(
                "expected >=1 mondrian.sql.execute span on cold-cache query"
                    + " — got " + sqlExecuteSpans.size(),
                sqlExecuteSpans.size() >= 1);
            assertTrue(
                "expected >=1 mondrian.cache.segment.load span on "
                    + "cold-cache query — got " + segmentLoadSpans.size(),
                segmentLoadSpans.size() >= 1);

            boolean foundSegmentLoadKind = false;
            for (SpanData s : sqlExecuteSpans) {
                String kind = s.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey(
                        "mondrian.sql.kind"));
                if ("segment-load".equals(kind)) {
                    foundSegmentLoadKind = true;
                    break;
                }
            }
            assertTrue(
                "expected >=1 mondrian.sql.execute span with "
                    + "mondrian.sql.kind=segment-load",
                foundSegmentLoadKind);
        } finally {
            mondrian.olap.MondrianProperties.instance()
                .DisableCaching.set(savedDisableCaching);
        }
    }

    /**
     * Parse failures (e.g. reference to a non-existent member) emit a
     * parse span with ERROR status + an exception event attached. Trace
     * viewers can turn that into a red flag instead of silent success.
     */
    @Test
    public void parseFailureRecordsExceptionOnParseSpan() {
        TestContext ctx = TestContext.instance();
        Connection conn = ctx.getConnection();
        try {
            try {
                conn.parseQuery(
                    "SELECT [Measures].[Nonexistent Member] "
                    + "ON COLUMNS FROM [Sales]");
            } catch (RuntimeException expected) {
                // ok — we want this to throw
            }
        } finally {
            conn.close();
        }

        // Find the FAILING parse span — there will be many successful
        // parses from schema init; ours is the one with ERROR status.
        SpanData parseSpan = null;
        for (SpanData s : exporter.getFinishedSpanItems()) {
            if ("mondrian.mdx.parse".equals(s.getName())
                && s.getStatus().getStatusCode() == StatusCode.ERROR)
            {
                parseSpan = s;
                break;
            }
        }
        assertNotNull(
            "expected a failing mondrian.mdx.parse span (status=ERROR)",
            parseSpan);
        assertTrue(
            "expected at least one exception event on the failing parse span",
            !parseSpan.getEvents().isEmpty());
    }
}

// End MdxExecuteSpanTest.java
