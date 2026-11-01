package io.github.hinn1411.docviewer.api;

import io.github.hinn1411.docviewer.api.dto.SourceResponse;
import io.github.hinn1411.docviewer.search.AuditUnavailableException;
import io.github.hinn1411.docviewer.search.model.InvalidVinException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into RFC 9457 problem details with the request ID. It extends Spring's handler
 * so that the catch-all doesn't turn Spring's own errors, such as 404 or 405, into 500.
 */
@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

  @ExceptionHandler
  ResponseEntity<Object> invalidVin(InvalidVinException failure, WebRequest request) {
    return respond(
        failure, problem(HttpStatus.BAD_REQUEST, "invalid-vin", failure.getMessage()), request);
  }

  @ExceptionHandler
  ResponseEntity<Object> missingCaller(MissingCallerException failure, WebRequest request) {
    return respond(
        failure, problem(HttpStatus.UNAUTHORIZED, "missing-caller", failure.getMessage()), request);
  }

  @ExceptionHandler
  ResponseEntity<Object> sourcesUnavailable(
      SourcesUnavailableException failure, WebRequest request) {
    ProblemDetail problem =
        problem(HttpStatus.SERVICE_UNAVAILABLE, "sources-unavailable", failure.getMessage());
    problem.setProperty("sources", failure.sources().stream().map(SourceResponse::from).toList());
    return respond(failure, problem, request);
  }

  @ExceptionHandler
  ResponseEntity<Object> auditUnavailable(AuditUnavailableException failure, WebRequest request) {
    return respond(
        failure,
        problem(HttpStatus.SERVICE_UNAVAILABLE, "audit-unavailable", failure.getMessage()),
        request);
  }

  /** Logs the class only, because an exception's message can contain the VIN. */
  @ExceptionHandler
  ResponseEntity<Object> unexpected(Exception failure, WebRequest request) {
    log.error("Unexpected error: failure={}", failure.getClass().getName());
    return respond(
        failure,
        problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "An unexpected error occurred"),
        request);
  }

  /** Every error passes through here, including the ones Spring handles itself. */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception failure,
      Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ResponseEntity<Object> response =
        super.handleExceptionInternal(failure, body, headers, status, request);
    if (response != null && response.getBody() instanceof ProblemDetail problem) {
      problem.setProperty(
          "requestId",
          ((ServletWebRequest) request)
              .getResponse()
              .getHeader(ResponseHeadersFilter.REQUEST_ID_HEADER));
    }
    return response;
  }

  private ResponseEntity<Object> respond(
      Exception failure, ProblemDetail problem, WebRequest request) {
    return handleExceptionInternal(
        failure, problem, new HttpHeaders(), HttpStatusCode.valueOf(problem.getStatus()), request);
  }

  private static ProblemDetail problem(HttpStatus status, String type, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(type));
    return problem;
  }
}
