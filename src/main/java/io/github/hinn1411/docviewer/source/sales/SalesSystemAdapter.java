package io.github.hinn1411.docviewer.source.sales;

import io.github.hinn1411.docviewer.search.model.Document;
import io.github.hinn1411.docviewer.search.model.Vin;
import io.github.hinn1411.docviewer.source.DocumentSource;
import java.util.List;
import org.springframework.stereotype.Component;

/** Reads documents from the Sales System. Stub for T07's red step. */
@Component
public class SalesSystemAdapter implements DocumentSource {

  @Override
  public String name() {
    throw new UnsupportedOperationException("T07 green step");
  }

  @Override
  public List<Document> fetch(Vin vin) {
    throw new UnsupportedOperationException("T07 green step");
  }
}
