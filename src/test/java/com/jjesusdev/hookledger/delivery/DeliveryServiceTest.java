package com.jjesusdev.hookledger.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjesusdev.hookledger.config.HookledgerProperties;
import com.jjesusdev.hookledger.crypto.Hmac;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryServiceTest {

    private static final String SECRET = "target-secret";
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedSignature = new AtomicReference<>();
    private final AtomicReference<String> receivedTimestamp = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String timestamp = exchange.getRequestHeaders().getFirst("X-Hook-Timestamp");
            receivedBody.set(new String(body, StandardCharsets.UTF_8));
            receivedTimestamp.set(timestamp);
            receivedSignature.set(exchange.getRequestHeaders().getFirst("X-Hook-Signature"));
            exchange.sendResponseHeaders(responseStatus.get(), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void signsTheExactBodyItSends() throws Exception {
        DeliveryService service = new DeliveryService(objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
        HookledgerProperties.Target target = new HookledgerProperties.Target(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", SECRET);
        JsonNode payload = objectMapper.readTree("{\"type\":\"test.event\",\"data\":{\"n\":1}}");

        DeliveryService.DeliveryResult result = service.deliver(
                target, "demo", UUID.randomUUID(), "test.event", payload, 1);

        assertThat(result.successful()).isTrue();
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(receivedBody.get()).isEqualTo("{\"type\":\"test.event\",\"data\":{\"n\":1}}");
        assertThat(receivedSignature.get()).isEqualTo(
                Hmac.signHex(SECRET, receivedTimestamp.get(), receivedBody.get().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void reportsNon2xxAsFailure() {
        responseStatus.set(503);
        DeliveryService service = new DeliveryService(objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
        HookledgerProperties.Target target = new HookledgerProperties.Target(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", SECRET);

        DeliveryService.DeliveryResult result = service.deliver(
                target, "demo", UUID.randomUUID(), "test.event", objectMapper.createObjectNode(), 1);

        assertThat(result.successful()).isFalse();
        assertThat(result.statusCode()).isEqualTo(503);
        assertThat(result.error()).contains("503");
    }

    @Test
    void refusesToDeliverWithoutATarget() {
        DeliveryService service = new DeliveryService(objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));

        DeliveryService.DeliveryResult result = service.deliver(
                null, "demo", UUID.randomUUID(), "test.event", objectMapper.createObjectNode(), 1);

        assertThat(result.successful()).isFalse();
        assertThat(result.error()).contains("no target");
    }
}
