package io.github.hinn1411.docviewer.source;

import io.github.hinn1411.docviewer.search.model.Document;
import io.github.hinn1411.docviewer.search.model.Vin;
import java.util.List;

/** A system that holds documents for a vehicle. A new source is one new implementation. */
public interface DocumentSource {

  /** The source's label on its documents, such as {@code SALES}. */
  String name();

  /**
   * Returns the source's documents for this VIN.
   *
   * @throws SourceException on any failure
   */
  List<Document> fetch(Vin vin);
}
