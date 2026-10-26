package com.jjesusdev.hookledger.ingest;

import java.util.UUID;

public record IngestResponse(UUID id, String status, boolean duplicate) {
}
