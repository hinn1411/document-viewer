package io.github.hinn1411.docviewer.api;

import io.github.hinn1411.docviewer.api.dto.DocumentsResponse;
import io.github.hinn1411.docviewer.api.logging.SearchSummaryLog;
import io.github.hinn1411.docviewer.domain.Caller;
import io.github.hinn1411.docviewer.domain.DocumentSearchResult;
import io.github.hinn1411.docviewer.domain.SearchOutcome;
import io.github.hinn1411.docviewer.domain.Vin;
import io.github.hinn1411.docviewer.domain.error.AuditUnavailableException;
import io.github.hinn1411.docviewer.domain.error.SourcesUnavailableException;
import io.github.hinn1411.docviewer.observability.DocumentViewerMetrics;
import io.github.hinn1411.docviewer.search.DocumentSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Duration;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The one endpoint: every document for a vehicle. Errors are mapped by {@code ProblemHandler}. */
@RestController
public class DocumentController {

  private final DocumentSearchService searchService;
  private final DocumentViewerMetrics metrics;

  public DocumentController(DocumentSearchService searchService, DocumentViewerMetrics metrics) {
    this.searchService = searchService;
    this.metrics = metrics;
  }

  @GetMapping("/api/v1/vehicles/{vin}/documents")
  @Operation(summary = "List every document for a vehicle, from all source systems")
  @ApiResponse(responseCode = "200", description = "All sources, or a partial result")
  @ApiResponse(
      responseCode = "400",
      description = "Invalid VIN",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Missing or blank identity header",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "No source could answer, or the access could not be audited",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(implementation = ProblemDetail.class)))
  public DocumentsResponse documents(
      @Parameter(example = "WVWZZZ1JZXW000001") @PathVariable String vin,
      @Parameter(description = "Required: missing or blank gives 401", example = "user-1")
          @RequestHeader(name = "X-User-Id", required = false)
          String userId,
      @Parameter(description = "Required: missing or blank gives 401", example = "dealer-1")
          @RequestHeader(name = "X-Dealership-Id", required = false)
          String dealershipId) {
    long start = System.nanoTime();
    Caller caller = new Caller(userId, dealershipId);
    Vin parsedVin = Vin.parse(vin);
    DocumentSearchResult result;
    try {
      result = searchService.search(parsedVin, caller);
    } catch (RuntimeException failure) {
      int status = failure instanceof AuditUnavailableException ? 503 : 500;
      metrics.recordRequest("failed", status, elapsedSince(start));
      throw failure;
    }
    boolean failed = result.outcome() == SearchOutcome.FAILED;
    int status = failed ? 503 : 200;
    Duration elapsed = elapsedSince(start);
    SearchSummaryLog.write(caller, result, status, elapsed.toMillis());
    metrics.recordRequest(result.outcome().name().toLowerCase(Locale.ROOT), status, elapsed);
    if (failed) {
      throw new SourcesUnavailableException(result.sources());
    }
    return DocumentsResponse.from(result);
  }

  private static Duration elapsedSince(long startNanos) {
    return Duration.ofNanos(System.nanoTime() - startNanos);
  }
}
