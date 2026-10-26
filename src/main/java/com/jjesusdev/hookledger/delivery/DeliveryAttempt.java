package com.jjesusdev.hookledger.delivery;

import java.time.Instant;
import java.util.UUID;

public record DeliveryAttempt(
        long id,
        UUID eventId,
        int attempt,
        String target,
        Integer statusCode,
        DeliveryOutcome outcome,
        String error,
        Instant createdAt
) {
}
