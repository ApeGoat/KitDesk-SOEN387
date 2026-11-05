package com.kitdesk.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on the scheduled jobs (expiry, late-return reassignment, idempotency-key cleanup). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
