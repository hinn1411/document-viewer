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

/** Keeps the full VIN out of the spans Spring creates for HTTP requests (NFR3). */
@Configuration
public class TracingConfig {

  /**
   * The request path and the source URLs hold the VIN. Spans keep the {@code uri} template, such as
   * {@code /api/v1/vehicles/{vin}/documents}, and lose the full URL.
   */
  @Bean
  ObservationFilter dropFullUrl() {
    return context -> context.removeHighCardinalityKeyValue("http.url");
  }

  /**
   * Replaces Boot's handler for source call spans, changing only how a failure is recorded.
   * Spring's client errors carry the URL in their message, and the default handler copies the
   * message into the span when the call fails, before any filter runs. The order is Boot's, so this
   * handler still runs before the default one and sends {@code traceparent}.
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
