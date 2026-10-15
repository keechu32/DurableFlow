package io.durableflow.api;

import io.durableflow.model.FlowException;
import java.net.URI;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Problems {
  @ExceptionHandler(FlowException.class)
  public ProblemDetail domain(FlowException ex) {
    var p = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(ex.status()), ex.getMessage());
    p.setType(URI.create("urn:durableflow:problem:" + ex.code()));
    p.setProperty("code", ex.code());
    return p;
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  public ProblemDetail invalid(Exception ex) {
    return domain(FlowException.invalid("Malformed request"));
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  public ProblemDetail notFound(Exception ex) {
    return domain(FlowException.missing());
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  public ProblemDetail method(Exception ex) {
    return domain(new FlowException(405, "method-not-allowed", "HTTP method is not supported"));
  }

  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  public ProblemDetail database(Exception ex) {
    return domain(
        new FlowException(
            503,
            "database-unavailable",
            "Persistence is temporarily unavailable; retry with the same idempotency key"));
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail unexpected(Exception ex) {
    if (ex instanceof org.springframework.web.ErrorResponse error
        && error.getStatusCode().value() < 500) {
      return domain(
          new FlowException(
              error.getStatusCode().value(),
              "http-" + error.getStatusCode().value(),
              "The request could not be processed"));
    }
    org.slf4j.LoggerFactory.getLogger(Problems.class)
        .atError()
        .addKeyValue("error_type", ex.getClass().getName())
        .log("Unhandled request failure");
    var p =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed");
    p.setProperty("code", "internal-error");
    return p;
  }
}
