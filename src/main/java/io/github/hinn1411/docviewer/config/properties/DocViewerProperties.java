package io.github.hinn1411.docviewer.config.properties;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The {@code docviewer.*} settings in {@code application.yml}. */
@ConfigurationProperties("docviewer")
public record DocViewerProperties(
    Duration sourceDeadline,
    Duration fallbackMaxAge,
    ZoneId dealershipZone,
    Map<String, SourceClient> sources) {

  /** Where one source is and how long to wait for it. */
  public record SourceClient(URI baseUrl, Duration connectTimeout, Duration readTimeout) {}
}
