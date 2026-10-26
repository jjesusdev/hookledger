package com.jjesusdev.hookledger.ingest;

import com.jjesusdev.hookledger.event.EventStatus;

import java.util.UUID;

public record IngestResult(UUID id, boolean duplicate, EventStatus status) {
}
