package io.github.hinn1411.docviewer.config.execution;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled}, for the snapshot cleanup job. */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
