package com.jjesusdev.hookledger.outbox;

import com.jjesusdev.hookledger.config.HookledgerProperties;
import com.jjesusdev.hookledger.event.EventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OutboxPublisher {

    private final OutboxRepository outboxRepository;
    private final EventRepository eventRepository;
    private final EventPublisher eventPublisher;
    private final HookledgerProperties properties;
    private final Counter publishedCounter;

    public OutboxPublisher(OutboxRepository outboxRepository,
                           EventRepository eventRepository,
                           EventPublisher eventPublisher,
                           HookledgerProperties properties,
                           MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.eventRepository = eventRepository;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.publishedCounter = Counter.builder("hookledger.outbox.published.total")
                .description("Outbox rows published to the transport")
                .register(meterRegistry);
    }

    /**
     * Claims a batch, publishes it and marks it as published in the same transaction.
     * Delivery is at-least-once: a crash after publish but before commit can duplicate a message.
     */
    @Transactional
    public int publishBatch() {
        List<OutboxRow> rows = outboxRepository.claimBatch(properties.outbox().batchSize());
        for (OutboxRow row : rows) {
            eventPublisher.publish(row);
            outboxRepository.markPublished(row.id());
            eventRepository.markPublished(row.eventId());
            publishedCounter.increment();
        }
        return rows.size();
    }
}
