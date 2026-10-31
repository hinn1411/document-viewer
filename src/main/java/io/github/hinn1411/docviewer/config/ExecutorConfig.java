package io.github.hinn1411.docviewer.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The executor for source calls: one virtual thread per call, so waiting on a source is cheap. */
@Configuration
public class ExecutorConfig {

  @Bean
  ExecutorService sourceCallExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
