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

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

/**
 * Single source of truth for Mondrian's OpenTelemetry tracer.
 *
 * <p>Instrumentation throughout Mondrian goes through this class so
 * the instrumentation library name + version stay consistent and so
 * call sites don't repeat the {@link GlobalOpenTelemetry#getTracer}
 * lookup boilerplate.
 *
 * <h3>Zero-overhead when disabled</h3>
 *
 * <p>If no OpenTelemetry SDK is registered (the default for any
 * deployment that doesn't explicitly wire one in), the returned
 * {@link Tracer} is the OTel API's built-in no-op tracer:
 * {@link Tracer#spanBuilder} returns an invalid {@link Span} whose
 * {@code startSpan()}/{@code setAttribute()}/{@code end()} calls are
 * inlined no-ops. Instrumentation is zero-allocation, zero-CPU when
 * no SDK is wired.
 *
 * <h3>How to enable spans in production</h3>
 *
 * <p>Add an OpenTelemetry SDK + exporter to the runtime classpath and
 * configure via standard OTel environment variables
 * ({@code OTEL_SERVICE_NAME}, {@code OTEL_EXPORTER_OTLP_ENDPOINT}, etc.)
 * OR programmatically via {@link io.opentelemetry.sdk.OpenTelemetrySdk}.
 * No Mondrian-side configuration required.
 *
 * <h3>Span naming convention</h3>
 *
 * <p>Top-level operations follow a {@code mondrian.<area>.<verb>}
 * naming convention so they group cleanly in any trace viewer:
 *
 * <ul>
 *   <li>{@code mondrian.mdx.execute} — root span per MDX query
 *       execution. Wired in {@code RolapConnection.execute(Execution)}.</li>
 *   <li>{@code mondrian.mdx.parse}, {@code mondrian.mdx.validate},
 *       {@code mondrian.mdx.compile} — child spans (future sessions).</li>
 *   <li>{@code mondrian.cube.evaluate} — per-axis evaluation
 *       (future).</li>
 *   <li>{@code mondrian.cache.segment.lookup},
 *       {@code mondrian.cache.segment.load} — segment cache events
 *       (future).</li>
 *   <li>{@code mondrian.sql.execute} — per-JDBC-statement; kind
 *       attribute distinguishes {@code segment-load} /
 *       {@code member-read} / {@code drillthrough} / {@code calcite-accel}
 *       (future).</li>
 * </ul>
 *
 * <h3>Attribute naming convention</h3>
 *
 * <p>Mondrian-specific attribute keys are namespaced under {@code
 * mondrian.}. Reuse the OTel semantic-convention keys
 * ({@code db.system}, {@code db.statement}, etc.) where applicable —
 * generic dashboards can then surface Mondrian's spans alongside
 * other instrumented systems.
 */
public final class MondrianTracing {

    /** Instrumentation library identity. Visible in trace dashboards. */
    public static final String INSTRUMENTATION_NAME = "mondrian";

    /** Bumped per Mondrian release. Visible in trace dashboards. */
    public static final String INSTRUMENTATION_VERSION = "4.8.1.9";

    private MondrianTracing() {}

    /**
     * Returns the shared Mondrian {@link Tracer}. Cheap to call — the
     * underlying {@link GlobalOpenTelemetry#getTracer} call is itself
     * cached, but call sites that record many spans should still cache
     * the returned reference in a {@code static final} field for
     * vanilla good practice.
     */
    public static Tracer tracer() {
        return GlobalOpenTelemetry.getTracer(
            INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION);
    }
}

// End MondrianTracing.java
