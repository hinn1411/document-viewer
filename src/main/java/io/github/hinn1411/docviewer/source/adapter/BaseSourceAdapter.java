package io.github.hinn1411.docviewer.source.adapter;

import io.github.hinn1411.docviewer.config.DocViewerProperties;
import io.github.hinn1411.docviewer.config.SourceClientsConfig;
import io.github.hinn1411.docviewer.search.model.Document;
import io.github.hinn1411.docviewer.search.model.DocumentType;
import io.github.hinn1411.docviewer.search.model.FailureReason;
import io.github.hinn1411.docviewer.search.model.Vin;
import io.github.hinn1411.docviewer.source.DocumentSource;
import io.github.hinn1411.docviewer.source.failure.RetryOnTransientError;
import io.github.hinn1411.docviewer.source.failure.SourceException;
import java.net.URI;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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

  private final String name;
  private final RestClient restClient;

  protected BaseSourceAdapter(String name, DocViewerProperties.SourceClient settings) {
    this.name = name;
    this.restClient = SourceClientsConfig.restClient(settings);
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
      throw new SourceException(FailureReason.ERROR, name + " response is unreadable");
    }
    return items.stream().flatMap(item -> toDocumentOrDrop(item).stream()).toList();
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
      throw SourceException.from(failure);
    }
  }

  /** Empty for a bad item: one bad item is dropped and the rest of the response is still good. */
  private Optional<Document> toDocumentOrDrop(I item) {
    try {
      return Optional.of(toDocument(item));
    } catch (DateTimeException | IllegalArgumentException badItem) {
      return Optional.empty();
    }
  }
}
