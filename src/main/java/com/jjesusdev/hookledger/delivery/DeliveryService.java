package com.jjesusdev.hookledger.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjesusdev.hookledger.config.HookledgerProperties;
import com.jjesusdev.hookledger.crypto.Hmac;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final HttpClient http;

    public DeliveryService(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Re-signs the payload for the target with its own secret. The target must verify over the exact
     * bytes received, so the JSON is serialized once and reused for the signature and the body.
     */
    public DeliveryResult deliver(HookledgerProperties.Target target,
                                  String source,
                                  UUID eventId,
                                  String eventType,
                                  JsonNode payload,
                                  int attempt) {
        if (target == null || target.url().isBlank()) {
            return DeliveryResult.failure(0, "no target configured for source " + source);
        }
        if (target.secret().isBlank()) {
            return DeliveryResult.failure(0, "target secret is empty for source " + source);
        }

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(payload == null ? objectMapper.createObjectNode() : payload);
        } catch (JsonProcessingException e) {
            return DeliveryResult.failure(0, "cannot serialize payload: " + e.getOriginalMessage());
        }

        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String signature = Hmac.signHex(target.secret(), timestamp, body);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(target.url()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Hook-Source", source)
                    .header("X-Hook-Id", eventId.toString())
                    .header("X-Hook-Type", eventType == null ? "unknown" : eventType)
                    .header("X-Hook-Timestamp", timestamp)
                    .header("X-Hook-Signature", signature)
                    .header("X-Hook-Attempt", Integer.toString(attempt))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
        } catch (IllegalArgumentException e) {
            return DeliveryResult.failure(0, "invalid target url: " + e.getMessage());
        }

        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return DeliveryResult.success(response.statusCode());
            }
            return DeliveryResult.failure(response.statusCode(),
                    "target returned HTTP " + response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeliveryResult.failure(0, "delivery interrupted");
        } catch (IOException e) {
            log.debug("delivery to {} failed: {}", target.url(), e.toString());
            return DeliveryResult.failure(0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public record DeliveryResult(int statusCode, String error) {

        public static DeliveryResult success(int statusCode) {
            return new DeliveryResult(statusCode, null);
        }

        public static DeliveryResult failure(int statusCode, String error) {
            return new DeliveryResult(statusCode, error);
        }

        public boolean successful() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
