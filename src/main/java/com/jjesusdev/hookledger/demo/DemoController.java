package com.jjesusdev.hookledger.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjesusdev.hookledger.config.HookledgerProperties;
import com.jjesusdev.hookledger.crypto.Hmac;
import com.jjesusdev.hookledger.ingest.IngestResult;
import com.jjesusdev.hookledger.ingest.IngestService;
import com.jjesusdev.hookledger.ingest.WebhookRejectedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/demo")
@ConditionalOnProperty(name = "hookledger.demo.enabled", havingValue = "true")
public class DemoController {

    private static final int MAX_COUNT = 1_000;
    private static final int MAX_DUPLICATES = 500;

    private final IngestService ingestService;
    private final HookledgerProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public DemoController(IngestService ingestService,
                          HookledgerProperties properties,
                          ObjectMapper objectMapper,
                          Clock clock) {
        this.ingestService = ingestService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @PostMapping("/burst")
    public DemoBurstResponse burst(@RequestParam(defaultValue = "100") int count,
                                   @RequestParam(defaultValue = "10") int duplicates) {
        if (count < 1 || count > MAX_COUNT) {
            throw new WebhookRejectedException(400, "INVALID_BURST",
                    "count must be between 1 and " + MAX_COUNT);
        }
        if (duplicates < 0 || duplicates > MAX_DUPLICATES) {
            throw new WebhookRejectedException(400, "INVALID_BURST",
                    "duplicates must be between 0 and " + MAX_DUPLICATES);
        }

        HookledgerProperties.Source demoSource = properties.sources().get("demo");
        if (demoSource == null || demoSource.secret().isBlank()) {
            throw new IllegalStateException("Demo source secret is not configured");
        }
        String secret = demoSource.secret();
        String timestamp = Long.toString(clock.instant().getEpochSecond());

        long startedAt = System.nanoTime();
        List<GeneratedEvent> generated = new ArrayList<>(count);
        int accepted = 0;
        int duplicate = 0;

        for (int i = 0; i < count; i++) {
            String eventId = "demo-" + UUID.randomUUID();
            String body = toJson(Map.of(
                    "type", "demo.ping",
                    "data", Map.of("seq", i, "at", clock.instant().toString())
            ));
            IngestResult result = ingest(secret, timestamp, eventId, body);
            generated.add(new GeneratedEvent(eventId, body));
            if (result.duplicate()) {
                duplicate++;
            } else {
                accepted++;
            }
        }

        for (int i = 0; i < duplicates; i++) {
            GeneratedEvent event = generated.get(random.nextInt(generated.size()));
            IngestResult result = ingest(secret, timestamp, event.eventId(), event.body());
            if (result.duplicate()) {
                duplicate++;
            } else {
                accepted++;
            }
        }

        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        return new DemoBurstResponse(count + duplicates, accepted, duplicate, 0, durationMs);
    }

    private IngestResult ingest(String secret, String timestamp, String eventId, String body) {
        byte[] rawBody = body.getBytes(StandardCharsets.UTF_8);
        String signature = Hmac.signHex(secret, timestamp, rawBody);
        return ingestService.ingest(
                "demo",
                eventId,
                "demo.ping",
                timestamp,
                signature,
                rawBody,
                Map.of("userAgent", "hookledger-demo")
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize demo event", e);
        }
    }

    private record GeneratedEvent(String eventId, String body) {
    }
}
