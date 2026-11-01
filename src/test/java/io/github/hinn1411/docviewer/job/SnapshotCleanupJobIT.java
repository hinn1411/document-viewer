package io.github.hinn1411.docviewer.job;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hinn1411.docviewer.TestcontainersConfiguration;
import io.github.hinn1411.docviewer.domain.Snapshot;
import io.github.hinn1411.docviewer.domain.Vin;
import io.github.hinn1411.docviewer.persistence.SnapshotRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;

/** T11: the job keeps copies for 24 h. It's called directly, so the test needn't wait an hour. */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class SnapshotCleanupJobIT {

  @Autowired SnapshotCleanupJob job;
  @Autowired SnapshotRepository repository;
  @Autowired Clock clock;

  @Test
  void deletesExpiredSnapshots() {
    Vin expired = Vin.parse("WVWZZZ1JZXW000031");
    Vin fresh = Vin.parse("WVWZZZ1JZXW000032");
    Instant now = clock.instant();
    repository.save(new Snapshot(expired, "SALES", List.of(), now.minus(Duration.ofHours(25))));
    repository.save(new Snapshot(fresh, "SALES", List.of(), now.minus(Duration.ofHours(23))));

    job.deleteExpiredSnapshots();

    assertThat(repository.findNewerThan(expired, "SALES", Instant.EPOCH)).isEmpty();
    assertThat(repository.findNewerThan(fresh, "SALES", Instant.EPOCH)).isPresent();
  }
}
