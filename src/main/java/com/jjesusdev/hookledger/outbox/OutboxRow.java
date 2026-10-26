package com.jjesusdev.hookledger.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxRow(
        long id,
        UUID eventId,
        String payload,
        int attempts,
        Instant createdAt
) {
}
