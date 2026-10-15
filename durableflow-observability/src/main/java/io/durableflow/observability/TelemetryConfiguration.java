package io.durableflow.observability;

import io.opentelemetry.api.common.*;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class TelemetryConfiguration {
  @Bean
  @ConditionalOnMissingBean(SpanExporter.class)
  public SpanExporter exporter(Environment env) {
    if (!env.getProperty("durableflow.telemetry.export", Boolean.class, false))
      return SpanExporter.composite();
    return OtlpGrpcSpanExporter.builder()
        .setEndpoint(env.getProperty("DURABLEFLOW_OTLP_ENDPOINT", "http://collector:4317"))
        .setTimeout(java.time.Duration.ofSeconds(2))
        .build();
  }

  @Bean(destroyMethod = "close")
  public OpenTelemetrySdk telemetrySdk(SpanExporter exporter, Environment env) {
    var provider =
        SdkTracerProvider.builder()
            .setResource(
                Resource.getDefault()
                    .merge(
                        Resource.create(
                            Attributes.of(
                                AttributeKey.stringKey("service.name"),
                                env.getProperty("spring.application.name", "durableflow"),
                                AttributeKey.stringKey("service.version"),
                                "0.1.0-SNAPSHOT"))))
            .addSpanProcessor(
                BatchSpanProcessor.builder(exporter)
                    .setScheduleDelay(java.time.Duration.ofMillis(200))
                    .build())
            .build();
    return OpenTelemetrySdk.builder()
        .setTracerProvider(provider)
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build();
  }
}
