package com.jjesusdev.hookledger.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Week 1 publisher: proves the outbox contract without an external broker.
 * Week 2 replaces this bean with the SQS adapter behind the same {@link EventPublisher} seam.
 */
@Component
@ConditionalOnProperty(name = "hookledger.publisher", havingValue = "log", matchIfMissing = true)
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(OutboxRow row) {
        log.debug("outbox event published id={} eventId={} attempts={}", row.id(), row.eventId(), row.attempts());
    }
}
