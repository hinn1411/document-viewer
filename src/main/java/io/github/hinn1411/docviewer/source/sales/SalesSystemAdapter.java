package io.github.hinn1411.docviewer.source.sales;

import io.github.hinn1411.docviewer.config.DocViewerProperties;
import io.github.hinn1411.docviewer.search.model.Document;
import io.github.hinn1411.docviewer.search.model.DocumentType;
import io.github.hinn1411.docviewer.search.model.Vin;
import io.github.hinn1411.docviewer.source.BaseSourceAdapter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Reads documents from the Sales System, where they are nested under deals. */
@Component
public class SalesSystemAdapter extends BaseSourceAdapter<SalesSystemAdapter.SalesDocument> {

  // STRICT rejects days that don't exist, such as 31/02/2025.
  private static final DateTimeFormatter CREATED_ON =
      DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

  private final ZoneId dealershipZone;

  public SalesSystemAdapter(DocViewerProperties properties) {
    super("SALES", properties.sources().get("sales"));
    this.dealershipZone = properties.dealershipZone();
  }

  @Override
  protected List<SalesDocument> readItems(RestClient client, Vin vin) {
    DealsResponse response =
        client
            .get()
            .uri(uri -> uri.path("/sales/v1/deals").queryParam("vin", vin.value()).build())
            .retrieve()
            .body(DealsResponse.class);
    if (response == null || response.deals() == null || response.deals().contains(null)) {
      return null;
    }
    return response.deals().stream()
        .filter(deal -> deal.documents() != null)
        .flatMap(deal -> deal.documents().stream())
        .toList();
  }

  @Override
  protected Document toDocument(SalesDocument item) {
    return document(
        item.docRef(),
        typeOf(item.docType()),
        item.name(),
        parseDate(item.createdOn()),
        item.link());
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

  private record DealsResponse(List<Deal> deals) {}

  private record Deal(List<SalesDocument> documents) {}

  /** Package-private only because it appears in this class's {@code extends} clause. */
  record SalesDocument(String docRef, String docType, String name, String createdOn, String link) {}
}
