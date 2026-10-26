package com.jjesusdev.hookledger.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjesusdev.hookledger.config.HookledgerProperties;
import com.jjesusdev.hookledger.event.EventRepository;
import com.jjesusdev.hookledger.event.EventRow;
import com.jjesusdev.hookledger.event.EventStatus;
import com.jjesusdev.hookledger.outbox.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final int MAX_SOURCE_LENGTH = 64;
    private static final int MAX_EXTERNAL_ID_LENGTH = 128;
    private static final int MAX_EVENT_TYPE_LENGTH = 128;

    private final HmacVerifier hmacVerifier;
    private final EventRepository eventRepository;
    private final OutboxRepository outboxRepository;
    private final HookledgerProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Counter acceptedCounter;
    private final Counter duplicateCounter;
    private final Counter conflictCounter;

    public IngestService(HmacVerifier hmacVerifier,
                         EventRepository eventRepository,
                         OutboxRepository outboxRepository,
                         HookledgerProperties properties,
                         ObjectMapper objectMapper,
                         Clock clock,
                         MeterRegistry meterRegistry) {
        this.hmacVerifier = hmacVerifier;
        this.eventRepository = eventRepository;
        this.outboxRepository = outboxRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.acceptedCounter = meterRegistry.counter("hookledger.ingest.total", "result", "accepted");
        this.duplicateCounter = meterRegistry.counter("hookledger.ingest.total", "result", "duplicate");
        this.conflictCounter = meterRegistry.counter("hookledger.ingest.total", "result", "conflict");
    }

    /**
     * Validates the signature, stores the event and appends to the outbox in one transaction.
     * The unique constraint is the idempotency gate; there is no read-then-write race.
     */
    @Transactional
    public IngestResult ingest(String source,
                               String externalId,
                               String eventType,
                               String timestamp,
                               String signature,
                               byte[] rawBody,
                               Map<String, String> metadata) {
        validateHeaders(source, externalId, timestamp);

        if (rawBody == null || rawBody.length == 0) {
            throw new WebhookRejectedException(400, "EMPTY_BODY", "Request body is required");
        }
        if (rawBody.length > properties.security().maxBodyBytes()) {
            throw new WebhookRejectedException(413, "PAYLOAD_TOO_LARGE",
                    "Request body exceeds " + properties.security().maxBodyBytes() + " bytes");
        }

        hmacVerifier.verify(source, timestamp, signature, rawBody);

        JsonNode body;
        try {
            body = objectMapper.readTree(rawBody);
        } catch (IOException e) {
            throw new WebhookRejectedException(400, "INVALID_JSON", "Request body must be valid JSON");
        }
        if (body == null || !body.isObject()) {
            throw new WebhookRejectedException(400, "INVALID_JSON", "Request body must be a JSON object");
        }

        String type = eventType == null || eventType.isBlank()
                ? body.path("type").asText("unknown")
                : eventType;
        if (type.length() > MAX_EVENT_TYPE_LENGTH) {
            throw new WebhookRejectedException(400, "EVENT_TYPE_TOO_LONG",
                    "Event type exceeds " + MAX_EVENT_TYPE_LENGTH + " characters");
        }

        String payloadHash = sha256Hex(rawBody);
        UUID id = UUID.randomUUID();
        String headersJson = toJson(Map.of(
                "userAgent", metadata == null ? "" : metadata.getOrDefault("userAgent", ""),
                "source", source,
                "externalId", externalId,
                "timestamp", timestamp
        ));

        boolean inserted = eventRepository.insertIfAbsent(
                id,
                source,
                externalId,
                type,
                new String(rawBody, StandardCharsets.UTF_8),
                payloadHash,
                headersJson
        );

        if (!inserted) {
            EventRow existing = eventRepository.findBySourceAndExternalId(source, externalId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency conflict but the existing event could not be loaded"));

            if (!existing.payloadHash().equals(payloadHash)) {
                conflictCounter.increment();
                throw new WebhookRejectedException(409, "IDEMPOTENCY_CONFLICT",
                        "The same event id was already used with a different payload",
                        Map.of("existingId", existing.id().toString()));
            }

            duplicateCounter.increment();
            return new IngestResult(existing.id(), true, existing.status());
        }

        String envelope = toJson(Map.of(
                "id", id.toString(),
                "source", source,
                "type", type,
                "receivedAt", clock.instant().toString(),
                "payload", body
        ));
        outboxRepository.insert(id, envelope);
        acceptedCounter.increment();

        log.debug("accepted source={} externalId={} id={}", source, externalId, id);
        return new IngestResult(id, false, EventStatus.RECEIVED);
    }

    private void validateHeaders(String source, String externalId, String timestamp) {
        if (source == null || source.isBlank() || source.length() > MAX_SOURCE_LENGTH) {
            throw new WebhookRejectedException(400, "INVALID_SOURCE", "X-Hook-Source is required");
        }
        if (externalId == null || externalId.isBlank() || externalId.length() > MAX_EXTERNAL_ID_LENGTH) {
            throw new WebhookRejectedException(400, "INVALID_EVENT_ID", "X-Hook-Id is required");
        }
        if (timestamp == null || timestamp.isBlank()) {
            throw new WebhookRejectedException(400, "INVALID_TIMESTAMP", "X-Hook-Timestamp is required");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize JSON", e);
        }
    }

    private static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
