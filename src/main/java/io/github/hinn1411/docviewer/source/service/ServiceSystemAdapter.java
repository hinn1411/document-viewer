package io.github.hinn1411.docviewer.source.service;

import io.github.hinn1411.docviewer.config.DocViewerProperties;
import io.github.hinn1411.docviewer.search.model.Document;
import io.github.hinn1411.docviewer.search.model.DocumentType;
import io.github.hinn1411.docviewer.search.model.Vin;
import io.github.hinn1411.docviewer.source.BaseSourceAdapter;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Reads documents from the Service System, which returns a flat list of records. */
@Component
public class ServiceSystemAdapter extends BaseSourceAdapter<ServiceSystemAdapter.ServiceRecord> {

  public ServiceSystemAdapter(DocViewerProperties properties) {
    super("SERVICE", properties.sources().get("service"));
  }

  @Override
  protected List<ServiceRecord> readItems(RestClient client, Vin vin) {
    return client
        .get()
        .uri("/service/api/vehicles/{vin}/records", vin.value())
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
  }

  @Override
  protected Document toDocument(ServiceRecord record) {
    return document(
        Objects.toString(record.recordId(), null),
        typeOf(record.category()),
        record.description(),
        record.completedAt() == null ? null : Instant.ofEpochSecond(record.completedAt()),
        record.fileUrl());
  }

  private static DocumentType typeOf(String category) {
    return switch (category) {
      case "JOB_CARD" -> DocumentType.JOB_CARD;
      case "MOT" -> DocumentType.MOT_CERTIFICATE;
      case null, default -> DocumentType.OTHER;
    };
  }

  /** Package-private only because it appears in this class's {@code extends} clause. */
  record ServiceRecord(
      Long recordId, String category, String description, Long completedAt, String fileUrl) {}
}
