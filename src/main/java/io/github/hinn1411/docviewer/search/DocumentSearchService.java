package io.github.hinn1411.docviewer.search;

import io.github.hinn1411.docviewer.config.DocViewerProperties;
import io.github.hinn1411.docviewer.search.model.DocumentSearchResult;
import io.github.hinn1411.docviewer.search.model.Vin;
import io.github.hinn1411.docviewer.source.DocumentSource;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Service;

/** Searches every source at once. Stub for T09's red step. */
@Service
public class DocumentSearchService {

  public DocumentSearchService(
      List<DocumentSource> sources,
      ResultMerger merger,
      ExecutorService sourceCallExecutor,
      DocViewerProperties properties) {}

  public DocumentSearchResult search(Vin vin) {
    throw new UnsupportedOperationException("T09 green step");
  }
}
