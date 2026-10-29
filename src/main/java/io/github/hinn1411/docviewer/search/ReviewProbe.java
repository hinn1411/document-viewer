package io.github.hinn1411.docviewer.search;

import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class ReviewProbe {
  private static final Logger log = LoggerFactory.getLogger(ReviewProbe.class);

  void logSearch(String vin) {
    log.info("Document search started for VIN {} at {}", vin, LocalDateTime.now());
  }
}
