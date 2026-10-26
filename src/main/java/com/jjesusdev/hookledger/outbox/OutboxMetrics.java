package com.jjesusdev.hookledger.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Backlog gauges for alerting. They are sampled on scrape instead of being updated by a scheduled
 * job, so the value always reflects the database at the moment Prometheus asks.
 */
@Component
public class OutboxMetrics {

    public OutboxMetrics(OutboxRepository outboxRepository, MeterRegistry meterRegistry, Clock clock) {
        Gauge.builder("hookledger.outbox.pending", outboxRepository, OutboxRepository::countPending)
                .description("Rows in outbox_event that have not been published yet")
                .register(meterRegistry);

        Gauge.builder("hookledger.outbox.oldest.age.seconds", outboxRepository,
                        repository -> ageSeconds(repository, clock))
                .description("Age of the oldest unpublished outbox row, in seconds")
                .register(meterRegistry);
    }

    private static double ageSeconds(OutboxRepository repository, Clock clock) {
        Instant oldest = repository.oldestPendingCreatedAt();
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, clock.instant()).toSeconds());
    }
}
