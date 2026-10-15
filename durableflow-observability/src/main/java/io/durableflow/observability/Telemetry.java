package io.durableflow.observability;

import io.micrometer.core.instrument.*;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.*;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public final class Telemetry {
  private final OpenTelemetrySdk sdk;
  private final MeterRegistry registry;
  private static final TextMapGetter<Map<String, String>> GETTER =
      new TextMapGetter<>() {
        public Iterable<String> keys(Map<String, String> carrier) {
          return carrier == null ? List.of() : carrier.keySet();
        }

        public String get(Map<String, String> carrier, String key) {
          return carrier == null ? null : carrier.get(key);
        }
      };

  public Telemetry(OpenTelemetrySdk sdk, MeterRegistry registry) {
    this.sdk = sdk;
    this.registry = registry;
    Map.of(
            "workflows.submitted",
            List.of("ACCEPTED"),
            "workflows.completed",
            List.of("SUCCEEDED", "FAILED", "CANCELLED"),
            "tasks.claimed",
            List.of("LEASED"),
            "tasks.completed",
            List.of("SUCCEEDED", "RETRY_WAIT", "DEAD_LETTERED"),
            "retry.attempts",
            List.of("CLAIMED"),
            "dead.lettered",
            List.of("DEAD_LETTERED"),
            "lease.expirations",
            List.of("TIMEOUT", "LEASE_EXPIRED"))
        .forEach(
            (name, statuses) ->
                statuses.forEach(
                    status -> registry.counter("durableflow." + name, "status", status)));
    for (String name : List.of("scheduling.latency", "execution.duration"))
      io.micrometer.core.instrument.Timer.builder("durableflow." + name)
          .publishPercentileHistogram()
          .register(registry);
  }

  public Trace start(String name, String parent, Map<String, String> attributes) {
    return start(name, parent, attributes, SpanKind.INTERNAL);
  }

  public Trace start(String name, String parent, Map<String, String> attributes, SpanKind kind) {
    Context context =
        parent == null
            ? Context.current()
            : sdk.getPropagators()
                .getTextMapPropagator()
                .extract(Context.root(), Map.of("traceparent", parent), GETTER);
    var span =
        sdk.getTracer("io.durableflow")
            .spanBuilder(name)
            .setParent(context)
            .setSpanKind(kind)
            .startSpan();
    attributes.forEach(span::setAttribute);
    var previous = MDC.getCopyOfContextMap();
    var scope = span.makeCurrent();
    MDC.put("trace_id", span.getSpanContext().getTraceId());
    MDC.put("span_id", span.getSpanContext().getSpanId());
    attributes.forEach(MDC::put);
    return new Trace(span, scope, previous);
  }

  public String traceParent() {
    var context = Span.current().getSpanContext();
    return context.isValid()
        ? "00-"
            + context.getTraceId()
            + "-"
            + context.getSpanId()
            + "-"
            + context.getTraceFlags().asHex()
        : null;
  }

  public void workflow(UUID id) {
    Span.current().setAttribute("workflow_id", id.toString());
  }

  public void count(String name, String status) {
    registry.counter("durableflow." + name, "status", status).increment();
  }

  public void timing(String name, long nanoseconds) {
    io.micrometer.core.instrument.Timer.builder("durableflow." + name)
        .publishPercentileHistogram()
        .register(registry)
        .record(Math.max(0, nanoseconds), TimeUnit.NANOSECONDS);
  }

  public static final class Trace implements AutoCloseable {
    private final Span span;
    private final Scope scope;
    private final Map<String, String> previous;

    private Trace(Span span, Scope scope, Map<String, String> previous) {
      this.span = span;
      this.scope = scope;
      this.previous = previous;
    }

    public void error() {
      span.setStatus(StatusCode.ERROR);
    }

    @Override
    public void close() {
      scope.close();
      span.end();
      if (previous == null) MDC.clear();
      else MDC.setContextMap(previous);
    }
  }
}
