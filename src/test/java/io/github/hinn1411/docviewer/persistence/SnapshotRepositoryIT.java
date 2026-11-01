package io.github.hinn1411.docviewer.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hinn1411.docviewer.TestcontainersConfiguration;
import io.github.hinn1411.docviewer.search.model.Document;
import io.github.hinn1411.docviewer.search.model.DocumentType;
import io.github.hinn1411.docviewer.search.model.Snapshot;
import io.github.hinn1411.docviewer.search.model.Vin;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;

/** T11: fallback copies in a real PostgreSQL. Each test uses its own VIN. */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class SnapshotRepositoryIT {

  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private static final Instant LONG_AGO = NOW.minus(Duration.ofDays(30));
  private static final Document DATED =
      new Document(
          "SALES",
          "INV-1",
          DocumentType.SALES_INVOICE,
          "Sales invoice",
          Instant.parse("2025-03-14T00:00:00Z"),
          URI.create("https://sales.example.com/docs/INV-1"),
          false);
  private static final Document UNDATED =
      new Document(
          "SALES",
          "ORD-1",
          DocumentType.ORDER_FORM,
          "Order form",
          null,
          URI.create("https://sales.example.com/docs/ORD-1"),
          false);

  @Autowired SnapshotRepository repository;

  @Test
  void newerCopyWins() {
    Vin vin = Vin.parse("WVWZZZ1JZXW000021");

    repository.save(new Snapshot(vin, "SALES", List.of(DATED), NOW));
    repository.save(new Snapshot(vin, "SALES", List.of(UNDATED), NOW.minus(Duration.ofHours(1))));

    Snapshot kept = repository.findNewerThan(vin, "SALES", LONG_AGO).orElseThrow();
    assertThat(kept.documents()).containsExactly(DATED);
    assertThat(kept.fetchedAt()).isEqualTo(NOW);
  }

  @Test
  void readsBackSavedDocuments() {
    Vin vin = Vin.parse("WVWZZZ1JZXW000022");
    Snapshot withDocuments = new Snapshot(vin, "SALES", List.of(DATED, UNDATED), NOW);
    Snapshot empty = new Snapshot(vin, "SERVICE", List.of(), NOW);

    repository.save(withDocuments);
    repository.save(empty);

    assertThat(repository.findNewerThan(vin, "SALES", LONG_AGO)).contains(withDocuments);
    assertThat(repository.findNewerThan(vin, "SERVICE", LONG_AGO)).contains(empty);
  }

  @Test
  void ignoresCopiesOlderThan24h() {
    Vin vin = Vin.parse("WVWZZZ1JZXW000023");
    Instant cutoff = NOW.minus(Duration.ofHours(24));

    repository.save(new Snapshot(vin, "SALES", List.of(DATED), NOW.minus(Duration.ofHours(25))));

    assertThat(repository.findNewerThan(vin, "SALES", cutoff)).isEmpty();
  }
}
