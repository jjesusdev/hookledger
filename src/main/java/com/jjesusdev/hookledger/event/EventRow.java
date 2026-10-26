package com.jjesusdev.hookledger.event;

import java.time.Instant;
import java.util.UUID;

public record EventRow(
        UUID id,
        String source,
        String externalId,
        String eventType,
        String payloadHash,
        EventStatus status,
        Instant receivedAt,
        Instant publishedAt
) {
}
