package com.jjesusdev.hookledger.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "hookledger.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

    private final OutboxPublisher publisher;

    public OutboxScheduler(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${hookledger.outbox.poll-interval-ms:500}")
    public void poll() {
        try {
            int published = publisher.publishBatch();
            if (published > 0) {
                log.debug("published {} outbox events", published);
            }
        } catch (RuntimeException e) {
            log.error("outbox batch failed; rows will be retried", e);
        }
    }
}
