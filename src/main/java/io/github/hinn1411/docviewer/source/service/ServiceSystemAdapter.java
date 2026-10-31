package io.github.hinn1411.docviewer.source.service;

import io.github.hinn1411.docviewer.search.model.Document;
import io.github.hinn1411.docviewer.search.model.Vin;
import io.github.hinn1411.docviewer.source.DocumentSource;
import java.util.List;
import org.springframework.stereotype.Component;

/** Reads documents from the Service System. Stub for T08's red step. */
@Component
public class ServiceSystemAdapter implements DocumentSource {

  @Override
  public String name() {
    throw new UnsupportedOperationException("T08 green step");
  }

  @Override
  public List<Document> fetch(Vin vin) {
    throw new UnsupportedOperationException("T08 green step");
  }
}
