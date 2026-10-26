package com.jjesusdev.hookledger.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component("outbox")
public class OutboxHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(OutboxHealthIndicator.class);

    private final OutboxRepository outboxRepository;
    private final Clock clock;

    public OutboxHealthIndicator(OutboxRepository outboxRepository, Clock clock) {
        this.outboxRepository = outboxRepository;
        this.clock = clock;
    }

    @Override
    public Health health() {
        try {
            long pending = outboxRepository.countPending();
            Instant oldest = outboxRepository.oldestPendingCreatedAt();
            long ageSeconds = oldest == null
                    ? 0
                    : Math.max(0, Duration.between(oldest, clock.instant()).toSeconds());
            return Health.up()
                    .withDetail("pending", pending)
                    .withDetail("oldestAgeSeconds", ageSeconds)
                    .build();
        } catch (RuntimeException e) {
            // A health indicator must report DOWN, not turn /actuator/health into a 500.
            log.warn("outbox health check failed: {}", e.toString());
            return Health.down()
                    .withDetail("error", e.getClass().getSimpleName())
                    .build();
        }
    }
}
