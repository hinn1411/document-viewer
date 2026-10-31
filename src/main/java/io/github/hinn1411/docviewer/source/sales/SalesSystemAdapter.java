package io.github.hinn1411.docviewer.source.sales;

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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads documents from the Sales System, where they are nested under deals.
 *
 * <p>{@code @Retryable} is on the class, not on {@code fetch}. With a JDK proxy, Spring's retry
 * interceptor sees the interface method and checks only that method and the target class, so an
 * annotation on this {@code fetch} would be missed and nothing would be retried.
 */
@Component
@Retryable(
    includes = SourceException.class,
    predicate = RetryOnTransientError.class,
    maxRetries = 1,
    delay = 100)
public class SalesSystemAdapter implements DocumentSource {

  private static final String NAME = "SALES";
  // STRICT rejects days that don't exist, such as 31/02/2025.
  private static final DateTimeFormatter CREATED_ON =
      DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

  private final RestClient restClient;
  private final ZoneId dealershipZone;

  public SalesSystemAdapter(DocViewerProperties properties) {
    this.restClient = SourceClientsConfig.restClient(properties.sources().get("sales"));
    this.dealershipZone = properties.dealershipZone();
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public List<Document> fetch(Vin vin) {
    DealsResponse response = callSales(vin);
    if (response == null || response.deals() == null) {
      throw new SourceException(FailureReason.ERROR, "Sales response has no deals");
    }
    return response.deals().stream()
        .filter(deal -> deal != null && deal.documents() != null)
        .flatMap(deal -> deal.documents().stream())
        .filter(Objects::nonNull)
        .flatMap(item -> toDocument(item).stream())
        .toList();
  }

  private DealsResponse callSales(Vin vin) {
    try {
      return restClient
          .get()
          .uri(uri -> uri.path("/sales/v1/deals").queryParam("vin", vin.value()).build())
          .retrieve()
          .body(DealsResponse.class);
    } catch (RestClientException failure) {
      throw SourceException.from(failure);
    }
  }

  /** Empty for a bad item: one bad item is dropped and the rest of the response is still good. */
  private Optional<Document> toDocument(SalesDocument item) {
    if (item.docRef() == null || item.docRef().isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new Document(
              NAME,
              item.docRef(),
              typeOf(item.docType()),
              item.name(),
              parseDate(item.createdOn()),
              parseHttpsLink(item.link()),
              false));
    } catch (DateTimeParseException | IllegalArgumentException badItem) {
      return Optional.empty();
    }
  }

  private static DocumentType typeOf(String docType) {
    return switch (docType) {
      case "INV" -> DocumentType.SALES_INVOICE;
      case "ORD" -> DocumentType.ORDER_FORM;
      case null, default -> DocumentType.OTHER;
    };
  }

  private Instant parseDate(String createdOn) {
    if (createdOn == null) {
      return null;
    }
    return LocalDate.parse(createdOn, CREATED_ON).atStartOfDay(dealershipZone).toInstant();
  }

  private static URI parseHttpsLink(String link) {
    if (link == null) {
      throw new IllegalArgumentException("No link");
    }
    URI uri = URI.create(link);
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      throw new IllegalArgumentException("Not an https link");
    }
    return uri;
  }

  private record DealsResponse(List<Deal> deals) {}

  private record Deal(List<SalesDocument> documents) {}

  private record SalesDocument(
      String docRef, String docType, String name, String createdOn, String link) {}
}
