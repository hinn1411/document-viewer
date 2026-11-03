package io.github.hinn1411.docviewer.config.tracing;

import io.github.hinn1411.docviewer.observability.ClassOnlyError;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.transport.SenderContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/** Keeps the full VIN out of Spring's HTTP spans (NFR3). */
@Configuration
public class TracingConfig {

  /** The full URL holds the VIN. Spans keep the {@code uri} template. */
  @Bean
  ObservationFilter dropFullUrl() {
    return context -> context.removeHighCardinalityKeyValue("http.url");
  }

  /**
   * Boot's client-span handler, but a failure records its class only, as Spring's message holds the
   * URL. Boot's order keeps {@code traceparent} working.
   */
  @Bean
  @Order(MicrometerTracingAutoConfiguration.SENDER_TRACING_OBSERVATION_HANDLER_ORDER)
  PropagatingSenderTracingObservationHandler<SenderContext<?>> classOnlyErrorsOnSourceCalls(
      Tracer tracer, Propagator propagator) {
    return new PropagatingSenderTracingObservationHandler<>(tracer, propagator) {
      @Override
      public void onError(SenderContext<?> context) {
        getRequiredSpan(context).error(new ClassOnlyError(context.getError()));
      }
    };
  }
}
