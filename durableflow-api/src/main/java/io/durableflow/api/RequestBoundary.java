package io.durableflow.api;

import io.durableflow.observability.Telemetry;
import io.durableflow.persistence.Json;
import io.opentelemetry.api.trace.SpanKind;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-200)
public final class RequestBoundary extends OncePerRequestFilter {
  public static final int MAX_BYTES = 1048576;
  private final Json json;
  private final Telemetry telemetry;

  public RequestBoundary(Json json, Telemetry telemetry) {
    this.json = json;
    this.telemetry = telemetry;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try (var trace =
        telemetry.start(
            "control-plane.request",
            request.getHeader("traceparent"),
            Map.of("http.request.method", request.getMethod()),
            SpanKind.SERVER)) {
      if (request.getContentLengthLong() > MAX_BYTES) {
        reject(response);
        return;
      }
      byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
      if (body.length > MAX_BYTES) {
        reject(response);
        return;
      }
      var wrapped =
          new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
              var input = new ByteArrayInputStream(body);
              return new ServletInputStream() {
                public int read() {
                  return input.read();
                }

                public boolean isFinished() {
                  return input.available() == 0;
                }

                public boolean isReady() {
                  return true;
                }

                public void setReadListener(ReadListener listener) {
                  throw new UnsupportedOperationException("Blocking MVC input only");
                }
              };
            }

            @Override
            public BufferedReader getReader() {
              return new BufferedReader(
                  new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            }
          };
      try {
        chain.doFilter(wrapped, response);
      } finally {
        io.opentelemetry.api.trace.Span.current()
            .setAttribute("http.response.status_code", response.getStatus());
        if (response.getStatus() >= 500) trace.error();
      }
    }
  }

  private void reject(HttpServletResponse response) throws IOException {
    response.setStatus(413);
    response.setContentType("application/problem+json");
    response
        .getWriter()
        .write(
            json.write(
                Map.of(
                    "status",
                    413,
                    "code",
                    "payload-too-large",
                    "detail",
                    "Request body exceeds 1 MiB")));
  }
}
