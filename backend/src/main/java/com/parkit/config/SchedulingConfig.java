package com.parkit.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables background jobs unless {@code parkit.scheduler.enabled=false} (e.g. in tests). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "parkit.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
