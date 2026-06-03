# OpenTelemetry in Mondrian

Mondrian ships built-in OpenTelemetry instrumentation as of #33 (4.8.1.10+). API-only dependency at compile time — no SDK pulled in by default, no overhead until an SDK is wired at runtime.

## What's instrumented

### Spans

| Span | Where | Attributes |
|---|---|---|
| `mondrian.mdx.execute` | `RolapConnection.execute(Execution)` — root span per MDX query | `mondrian.schema`, `mondrian.cube`, `mondrian.execution.id` |
| `mondrian.mdx.parse` | `ConnectionBase.parseStatement` | `mondrian.mdx.length` |
| `mondrian.mdx.validate` | inside `Query.resolve()` | — |
| `mondrian.mdx.compile` | inside `Query.resolve()` | — |
| `mondrian.cache.segment.load` | `SegmentLoader.load` | `mondrian.cache.cell_request_count`, `mondrian.cache.grouping_set_count` |
| `mondrian.sql.execute` | `SqlStatement.execute` | `mondrian.sql.kind` (`segment-load` / `member-read` / `drillthrough` / `other`), `mondrian.sql.id` |

Span hierarchy on a typical cold-cache MDX query:

```
mondrian.mdx.execute
  └─ mondrian.cache.segment.load
       └─ mondrian.sql.execute (kind=segment-load)
```

Plus standalone `mondrian.mdx.parse` + nested validate/compile spans during `parseQuery` (separate trace — parse happens before execute).

### Metrics

| Metric | Type | Unit | Attributes |
|---|---|---|---|
| `mondrian.queries.executed` | counter | `{query}` | `mondrian.query.outcome` = `success` / `failure` |
| `mondrian.query.duration` | histogram | `ms` | `mondrian.query.outcome` |
| `mondrian.sql.statements` | counter | `{statement}` | `mondrian.sql.kind` |
| `mondrian.sql.duration` | histogram | `ms` | `mondrian.sql.kind` |
| `mondrian.cache.segment.hits` | counter | `{cell}` | — |
| `mondrian.cache.segment.misses` | counter | `{cell}` | — |
| `mondrian.calcite.fallback` | counter | `{fallback}` | `mondrian.calcite.fallback.site`, `mondrian.calcite.fallback.exception` |

The cache hit/miss counters are incremented per cell read in `FastBatchingCellReader` — `LongCounter.add` is an atomic CAS, comparable cost to the existing non-OTel `++hitCount` counter Mondrian already maintained. No measurable hot-path overhead added.

## Enabling in production

### Option 1 — Auto-config via environment variables

Add `io.opentelemetry:opentelemetry-sdk` + an exporter (e.g. `opentelemetry-exporter-otlp`) to your deployment classpath, then set the standard OTel environment variables. The SDK auto-discovers and registers itself.

```bash
export OTEL_SERVICE_NAME=mondrian-saiku
export OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
export OTEL_TRACES_EXPORTER=otlp
export OTEL_METRICS_EXPORTER=otlp
```

No Mondrian-side configuration required.

### Option 2 — Programmatic SDK setup

In your application startup (before any Mondrian connection is created):

```java
OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
    .setTracerProvider(
        SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(
                OtlpGrpcSpanExporter.builder()
                    .setEndpoint("http://otel-collector:4317")
                    .build()).build())
            .build())
    .setMeterProvider(
        SdkMeterProvider.builder()
            .registerMetricReader(
                PeriodicMetricReader.builder(
                    OtlpGrpcMetricExporter.builder()
                        .setEndpoint("http://otel-collector:4317")
                        .build()).build())
            .build())
    .build();
GlobalOpenTelemetry.set(sdk);
```

## Routing SLF4J logs through OTel

Mondrian uses SLF4J → log4j2 (per `pom.xml`). To route log4j2 logs into the OTel pipeline alongside spans + metrics, add the OTel log4j2 appender to your deployment classpath:

```xml
<!-- pom additions -->
<dependency>
  <groupId>io.opentelemetry.instrumentation</groupId>
  <artifactId>opentelemetry-log4j-appender-2.17</artifactId>
  <version>2.7.0-alpha</version>
</dependency>
```

Then in your runtime `log4j2.xml`:

```xml
<Configuration>
  <Appenders>
    <OpenTelemetry name="OpenTelemetryAppender"/>
    <Console name="Console" target="SYSTEM_OUT">
      <PatternLayout pattern="%d %p [%t] %c{1.} %m%n"/>
    </Console>
  </Appenders>
  <Loggers>
    <Root level="info">
      <AppenderRef ref="Console"/>
      <AppenderRef ref="OpenTelemetryAppender"/>
    </Root>
  </Loggers>
</Configuration>
```

All Mondrian log lines (parse errors, segment load timings, Calcite fallback WARN messages from #10) flow into the OTel log pipeline with the active trace + span ID auto-attached. Trace viewers can correlate logs with spans on the same MDX query.

## Verifying spans / metrics emit

The test class `mondrian.observability.MondrianMetricsTest` demonstrates the in-memory verification pattern using `InMemorySpanExporter` + `InMemoryMetricReader` — useful for local dev and as a template for production smoke tests.

## What's NOT instrumented (yet)

- `mondrian.cache.segment.lookup` per-cell span — deliberately omitted in favour of the hit/miss counters above (per-cell span would have prohibitive overhead).
- `mondrian.calcite.plan` / `calcite.execute` spans — Calcite-accel path emits SQL via a different code path that doesn't go through `SqlStatement`. Separate instrumentation point pending.
- `mondrian.connections.{active,total}` gauges — needs JDBC pool integration; deferred.
- `db.statement` attribute on `mondrian.sql.execute` — current spans don't include the SQL text. Add via `-Dmondrian.otel.capture-sql-text=true` if/when that capture-cost is worth it (typical SQL is 100-1000 chars per span; off by default for cardinality + privacy reasons).
