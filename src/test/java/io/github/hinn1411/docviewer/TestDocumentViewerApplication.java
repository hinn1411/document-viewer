package io.github.hinn1411.docviewer;

import org.springframework.boot.SpringApplication;

/**
 * Runs the app locally against a Testcontainers PostgreSQL: {@code ./mvnw spring-boot:test-run}.
 */
public class TestDocumentViewerApplication {

  public static void main(String[] args) {
    SpringApplication.from(DocumentViewerApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
