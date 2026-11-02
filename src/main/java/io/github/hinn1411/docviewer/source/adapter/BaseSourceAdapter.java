package io.github.hinn1411.docviewer.source.adapter;

import io.github.hinn1411.docviewer.config.client.SourceClientsConfig;
import io.github.hinn1411.docviewer.config.properties.DocViewerProperties;
import io.github.hinn1411.docviewer.domain.Document;
import io.github.hinn1411.docviewer.domain.DocumentType;
import io.github.hinn1411.docviewer.domain.FailureReason;
import io.github.hinn1411.docviewer.domain.Vin;
import io.github.hinn1411.docviewer.source.DocumentSource;
import io.github.hinn1411.docviewer.source.error.RetryOnTransientError;
import io.github.hinn1411.docviewer.source.error.SourceException;
import java.net.URI;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * What every source adapter shares: the call, its failures and dropping bad items. An adapter only
 * says how to read its source's response and how to map one item, of type {@code I}.
 *
 * <p>{@code @Retryable} is on a class, not on {@code fetch}. With a JDK proxy, Spring's retry
 * interceptor sees the interface method and checks only that method and the target class, so an
 * annotation on {@code fetch} would be missed and nothing would be retried.
 */
@Retryable(
    includes = SourceException.class,
    predicate = RetryOnTransientError.class,
    maxRetries = 1,
    delay = 100)
public abstract class BaseSourceAdapter<I> implements DocumentSource {

  private static final Logger log = LoggerFactory.getLogger(BaseSourceAdapter.class);

  private final String name;
  private final RestClient restClient;

  /** {@code builder} is Spring's auto-configured one, which gives the client its HTTP metrics. */
  protected BaseSourceAdapter(
      String name, RestClient.Builder builder, DocViewerProperties.SourceClient settings) {
    this.name = name;
    this.restClient = SourceClientsConfig.restClient(builder, settings);
  }

  /** Calls the source and returns its items, or null if the response is unreadable. */
  protected abstract List<I> readItems(RestClient client, Vin vin);

  /** Maps one item. Throws {@link IllegalArgumentException} or a date error for a bad item. */
  protected abstract Document toDocument(I item);

  @Override
  public String name() {
    return name;
  }

  @Override
  public List<Document> fetch(Vin vin) {
    List<I> items = callSource(vin);
    if (items == null || items.contains(null)) {
      log.atWarn()
          .setMessage("Unreadable source response")
          .addKeyValue("source", name)
          .addKeyValue("vin", vin.masked())
          .log();
      throw new SourceException(FailureReason.ERROR, name + " response is unreadable");
    }
    return items.stream().flatMap(item -> toDocumentOrDrop(item, vin).stream()).toList();
  }

  /** Builds a document, applying the rules every source shares. */
  protected Document document(
      String id, DocumentType type, String title, Instant date, String link) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("No ID");
    }
    if (link == null || !link.startsWith("https://")) {
      throw new IllegalArgumentException("Not an https link");
    }
    return new Document(name, id, type, title, date, URI.create(link), false);
  }

  private List<I> callSource(Vin vin) {
    try {
      return readItems(restClient, vin);
    } catch (RestClientException failure) {
      logFailedAttempt(vin, failure);
      throw SourceException.from(failure);
    }
  }

  /**
   * Per attempt, so a retry shows up. At INFO, because the search service writes the one WARN per
   * failed source. The class only, because Spring's messages contain the URL, and so the VIN.
   */
  private void logFailedAttempt(Vin vin, RestClientException failure) {
    Integer httpStatus =
        failure instanceof RestClientResponseException response
            ? response.getStatusCode().value()
            : null;
    log.atInfo()
        .setMessage("Source attempt failed")
        .addKeyValue("source", name)
        .addKeyValue("vin", vin.masked())
        .addKeyValue("failure", failure.getClass().getName())
        .addKeyValue("httpStatus", httpStatus)
        .log();
  }

  /** Empty for a bad item: one bad item is dropped and the rest of the response is still good. */
  private Optional<Document> toDocumentOrDrop(I item, Vin vin) {
    try {
      return Optional.of(toDocument(item));
    } catch (DateTimeException | IllegalArgumentException badItem) {
      log.atWarn()
          .setMessage("Dropped bad source item")
          .addKeyValue("source", name)
          .addKeyValue("vin", vin.masked())
          .addKeyValue("failure", badItem.getClass().getName())
          .log();
      return Optional.empty();
    }
  }
}
