package com.jjesusdev.hookledger.event;

import java.time.Instant;
import java.util.UUID;

public record EventView(
        UUID id,
        String source,
        String externalId,
        String eventType,
        String status,
        Instant receivedAt,
        Instant publishedAt
) {

    public static EventView from(EventRow row) {
        return new EventView(
                row.id(),
                row.source(),
                row.externalId(),
                row.eventType(),
                row.status().name(),
                row.receivedAt(),
                row.publishedAt()
        );
    }
}
