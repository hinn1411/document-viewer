package io.github.hinn1411.docviewer.source.service;

import io.github.hinn1411.docviewer.config.DocViewerProperties;
import io.github.hinn1411.docviewer.config.SourceClientsConfig;
import io.github.hinn1411.docviewer.search.model.Document;
import io.github.hinn1411.docviewer.search.model.DocumentType;
import io.github.hinn1411.docviewer.search.model.FailureReason;
import io.github.hinn1411.docviewer.search.model.Vin;
import io.github.hinn1411.docviewer.source.DocumentSource;
import io.github.hinn1411.docviewer.source.RetryOnTransientError;
import io.github.hinn1411.docviewer.source.SourceException;
import java.net.URI;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads documents from the Service System, which returns a flat list of records.
 *
 * <p>{@code @Retryable} is on the class for the reason given on {@code SalesSystemAdapter}.
 */
@Component
@Retryable(
    includes = SourceException.class,
    predicate = RetryOnTransientError.class,
    maxRetries = 1,
    delay = 100)
public class ServiceSystemAdapter implements DocumentSource {

  private static final String NAME = "SERVICE";

  private final RestClient restClient;

  public ServiceSystemAdapter(DocViewerProperties properties) {
    this.restClient = SourceClientsConfig.restClient(properties.sources().get("service"));
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public List<Document> fetch(Vin vin) {
    List<ServiceRecord> records = callService(vin);
    if (records == null || records.contains(null)) {
      throw new SourceException(FailureReason.ERROR, "Service response is unreadable");
    }
    return records.stream().flatMap(record -> toDocument(record).stream()).toList();
  }

  private List<ServiceRecord> callService(Vin vin) {
    try {
      return restClient
          .get()
          .uri("/service/api/vehicles/{vin}/records", vin.value())
          .retrieve()
          .body(new ParameterizedTypeReference<>() {});
    } catch (RestClientException failure) {
      throw SourceException.from(failure);
    }
  }

  /**
   * Empty for a bad record: one bad record is dropped and the rest of the response is still good.
   */
  private static Optional<Document> toDocument(ServiceRecord record) {
    boolean hasHttpsLink = record.fileUrl() != null && record.fileUrl().startsWith("https://");
    if (record.recordId() == null || !hasHttpsLink) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new Document(
              NAME,
              record.recordId().toString(),
              typeOf(record.category()),
              record.description(),
              record.completedAt() == null ? null : Instant.ofEpochSecond(record.completedAt()),
              URI.create(record.fileUrl()),
              false));
    } catch (DateTimeException | IllegalArgumentException badRecord) {
      return Optional.empty();
    }
  }

  private static DocumentType typeOf(String category) {
    return switch (category) {
      case "JOB_CARD" -> DocumentType.JOB_CARD;
      case "MOT" -> DocumentType.MOT_CERTIFICATE;
      case null, default -> DocumentType.OTHER;
    };
  }

  private record ServiceRecord(
      Long recordId, String category, String description, Long completedAt, String fileUrl) {}
}
