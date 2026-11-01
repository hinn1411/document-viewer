package io.github.hinn1411.docviewer.api.filter;

import io.github.hinn1411.docviewer.observability.TraceIds;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Adds the request ID (the trace ID, §6.2) and {@code no-store} to every response. */
@Component
public class ResponseHeadersFilter extends OncePerRequestFilter {

  public static final String REQUEST_ID_HEADER = "X-Request-Id";

  private final Tracer tracer;

  public ResponseHeadersFilter(Tracer tracer) {
    this.tracer = tracer;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader(REQUEST_ID_HEADER, TraceIds.current(tracer));
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    chain.doFilter(request, response);
  }
}
