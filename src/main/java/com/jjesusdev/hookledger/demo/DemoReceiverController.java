package com.jjesusdev.hookledger.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjesusdev.hookledger.config.HookledgerProperties;
import com.jjesusdev.hookledger.ingest.HmacVerifier;
import com.jjesusdev.hookledger.ingest.WebhookRejectedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Target used by the demo worker. It validates the target signature, so the smoke test proves the
 * full path: ingest -> outbox -> SQS -> worker -> signed HTTP delivery.
 */
@RestController
@RequestMapping("/v1/demo")
@ConditionalOnProperty(name = "hookledger.demo.enabled", havingValue = "true")
public class DemoReceiverController {

    private final HmacVerifier hmacVerifier;
    private final HookledgerProperties properties;
    private final ObjectMapper objectMapper;

    public DemoReceiverController(HmacVerifier hmacVerifier,
                                  HookledgerProperties properties,
                                  ObjectMapper objectMapper) {
        this.hmacVerifier = hmacVerifier;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/receive")
    public Map<String, Object> receive(
            @RequestHeader("X-Hook-Id") String eventId,
            @RequestHeader("X-Hook-Timestamp") String timestamp,
            @RequestHeader("X-Hook-Signature") String signature,
            @RequestHeader(value = "X-Hook-Source", required = false) String source,
            @RequestHeader(value = "X-Hook-Type", required = false) String eventType,
            @RequestBody byte[] body) {

        HookledgerProperties.Target target = properties.targets().get("demo");
        if (target == null || target.secret().isBlank()) {
            throw new WebhookRejectedException(500, "TARGET_NOT_CONFIGURED",
                    "Demo target secret is not configured");
        }
        hmacVerifier.verifyWithSecret(target.secret(), timestamp, signature, body);

        String type = eventType;
        if (type == null || type.isBlank()) {
            try {
                JsonNode json = objectMapper.readTree(body);
                type = json.path("type").asText("unknown");
            } catch (IOException e) {
                type = "unknown";
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("received", true);
        response.put("eventId", eventId);
        response.put("source", source == null ? "unknown" : source);
        response.put("type", type);
        return response;
    }
}
