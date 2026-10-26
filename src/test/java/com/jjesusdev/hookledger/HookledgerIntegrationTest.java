package com.jjesusdev.hookledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjesusdev.hookledger.crypto.Hmac;
import com.jjesusdev.hookledger.delivery.DeliveryOutcome;
import com.jjesusdev.hookledger.delivery.DeliveryRepository;
import com.jjesusdev.hookledger.demo.DemoBurstResponse;
import com.jjesusdev.hookledger.event.EventRepository;
import com.jjesusdev.hookledger.event.EventStatus;
import com.jjesusdev.hookledger.ingest.IngestResponse;
import com.jjesusdev.hookledger.ingest.IngestService;
import com.jjesusdev.hookledger.outbox.OutboxPublisher;
import com.jjesusdev.hookledger.support.EmbeddedPostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "hookledger.sources.demo.secret=test-secret",
                "hookledger.targets.demo.secret=dev-target-secret",
                "hookledger.demo.enabled=true",
                "hookledger.outbox.enabled=false",
                "spring.datasource.hikari.maximum-pool-size=20",
                "logging.level.com.jjesusdev.hookledger=WARN"
        }
)
@Import(EmbeddedPostgresTestConfig.class)
@Sql(scripts = "/clean.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class HookledgerIntegrationTest {

    private static final String SECRET = "test-secret";
    private static final String BODY = "{\"type\":\"test.event\",\"data\":{\"n\":1}}";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private EventRepository eventRepository;

    @Test
    void acceptsEventAndWritesOutboxInTheSameTransaction() {
        ResponseEntity<IngestResponse> response = post("evt-1", BODY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().duplicate()).isFalse();
        assertThat(response.getBody().status()).isEqualTo("RECEIVED");
        assertThat(count("SELECT count(*) FROM webhook_event")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_event")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE payload->>'source' = 'demo'")).isEqualTo(1);

        ResponseEntity<String> event = rest.getForEntity("/v1/events/" + response.getBody().id(), String.class);
        assertThat(event.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(event.getBody()).path("status").asText()).isEqualTo("RECEIVED");
    }

    @Test
    void duplicateWithSamePayloadIsIdempotentAndDoesNotAppendToOutbox() {
        ResponseEntity<IngestResponse> first = post("evt-dup", BODY);
        ResponseEntity<IngestResponse> second = post("evt-dup", BODY);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().duplicate()).isTrue();
        assertThat(second.getBody().id()).isEqualTo(first.getBody().id());
        assertThat(count("SELECT count(*) FROM webhook_event")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_event")).isEqualTo(1);
    }

    @Test
    void duplicateWithDifferentPayloadConflicts() {
        post("evt-conflict", BODY);

        ResponseEntity<String> response = rest.postForEntity(
                "/v1/events",
                signedRequest("evt-conflict", "{\"type\":\"test.event\",\"data\":{\"n\":2}}"),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = readJson(response.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(problem.path("existingId").asText()).isNotBlank();
        assertThat(count("SELECT count(*) FROM webhook_event")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_event")).isEqualTo(1);
    }

    @Test
    void invalidSignatureIsRejectedBeforePersisting() {
        long timestamp = Instant.now().getEpochSecond();

        ResponseEntity<String> response = rest.postForEntity(
                "/v1/events",
                signedRequest("evt-bad", BODY, timestamp, "00".repeat(32)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(readJson(response.getBody()).path("code").asText()).isEqualTo("INVALID_SIGNATURE");
        assertThat(count("SELECT count(*) FROM webhook_event")).isZero();
    }

    @Test
    void expiredTimestampIsRejected() {
        long timestamp = Instant.now().getEpochSecond() - 600;
        String signature = Hmac.signHex(SECRET, Long.toString(timestamp), BODY.getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = rest.postForEntity(
                "/v1/events",
                signedRequest("evt-old", BODY, timestamp, signature),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(readJson(response.getBody()).path("code").asText()).isEqualTo("TIMESTAMP_OUT_OF_WINDOW");
        assertThat(count("SELECT count(*) FROM webhook_event")).isZero();
    }

    @Test
    void concurrentDuplicatesCreateOneEventAndOneOutboxRow() throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        HttpEntity<byte[]> request = signedRequest(
                "evt-concurrent",
                BODY,
                timestamp,
                Hmac.signHex(SECRET, Long.toString(timestamp), BODY.getBytes(StandardCharsets.UTF_8))
        );

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<IngestResponse>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return rest.postForEntity("/v1/events", request, IngestResponse.class);
                }));
            }
            start.countDown();

            long accepted = 0;
            long duplicates = 0;
            for (Future<ResponseEntity<IngestResponse>> future : futures) {
                ResponseEntity<IngestResponse> response = future.get(15, TimeUnit.SECONDS);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(response.getBody()).isNotNull();
                if (response.getBody().duplicate()) {
                    duplicates++;
                } else {
                    accepted++;
                }
            }

            assertThat(accepted).isEqualTo(1);
            assertThat(duplicates).isEqualTo(threads - 1);
            assertThat(count("SELECT count(*) FROM webhook_event")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM outbox_event")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void demoBurstReportsAcceptedAndDuplicateEvents() {
        ResponseEntity<DemoBurstResponse> response = rest.postForEntity(
                "/v1/demo/burst?count=20&duplicates=5",
                HttpEntity.EMPTY,
                DemoBurstResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().requested()).isEqualTo(25);
        assertThat(response.getBody().accepted()).isEqualTo(20);
        assertThat(response.getBody().duplicates()).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM webhook_event")).isEqualTo(20);
        assertThat(count("SELECT count(*) FROM outbox_event")).isEqualTo(20);
    }

    @Test
    void outboxPublisherClaimsEachRowExactlyOnceUnderConcurrency() throws Exception {
        int events = 100;
        for (int i = 0; i < events; i++) {
            String body = "{\"type\":\"test.outbox\",\"data\":{\"n\":" + i + "}}";
            long timestamp = Instant.now().getEpochSecond();
            String signature = Hmac.signHex(SECRET, Long.toString(timestamp), body.getBytes(StandardCharsets.UTF_8));
            ingestService.ingest(
                    "demo",
                    "outbox-" + i,
                    "test.outbox",
                    Long.toString(timestamp),
                    signature,
                    body.getBytes(StandardCharsets.UTF_8),
                    Map.of("userAgent", "test")
            );
        }
        assertThat(count("SELECT count(*) FROM outbox_event")).isEqualTo(events);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    int published = 0;
                    int batch;
                    while ((batch = outboxPublisher.publishBatch()) > 0) {
                        published += batch;
                    }
                    return published;
                }));
            }
            start.countDown();

            int published = 0;
            for (Future<Integer> future : futures) {
                published += future.get(20, TimeUnit.SECONDS);
            }
            assertThat(published).isEqualTo(events);
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("SELECT count(*) FROM outbox_event WHERE published_at IS NOT NULL")).isEqualTo(events);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE published_at IS NULL")).isZero();
        assertThat(count("SELECT sum(attempts) FROM outbox_event")).isEqualTo(events);
        assertThat(count("SELECT max(attempts) FROM outbox_event")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM webhook_event WHERE status = 'PUBLISHED'")).isEqualTo(events);
    }

    @Test
    void demoReceiverAcceptsADeliverySignedWithTheTargetSecret() {
        String body = "{\"type\":\"demo.ping\",\"data\":{\"n\":1}}";
        long timestamp = Instant.now().getEpochSecond();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Hook-Id", "delivery-1");
        headers.set("X-Hook-Source", "demo");
        headers.set("X-Hook-Type", "demo.ping");
        headers.set("X-Hook-Timestamp", Long.toString(timestamp));
        headers.set("X-Hook-Signature",
                Hmac.signHex("dev-target-secret", Long.toString(timestamp), body.getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<String> response = rest.postForEntity(
                "/v1/demo/receive",
                new HttpEntity<>(body.getBytes(StandardCharsets.UTF_8), headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(response.getBody()).path("received").asBoolean()).isTrue();
        assertThat(readJson(response.getBody()).path("eventId").asText()).isEqualTo("delivery-1");
    }

    @Test
    void demoReceiverRejectsADeliveryWithTheWrongSecret() {
        String body = "{\"type\":\"demo.ping\"}";
        long timestamp = Instant.now().getEpochSecond();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Hook-Id", "delivery-bad");
        headers.set("X-Hook-Timestamp", Long.toString(timestamp));
        headers.set("X-Hook-Signature",
                Hmac.signHex("wrong-secret", Long.toString(timestamp), body.getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<String> response = rest.postForEntity(
                "/v1/demo/receive",
                new HttpEntity<>(body.getBytes(StandardCharsets.UTF_8), headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(readJson(response.getBody()).path("code").asText()).isEqualTo("INVALID_SIGNATURE");
    }

    @Test
    void deliveryAttemptsArePersistedAndExposedPerEvent() {
        ResponseEntity<IngestResponse> created = post("evt-delivery", BODY);
        UUID eventId = created.getBody().id();
        deliveryRepository.record(eventId, 1, "http://target.test/hook", 200, DeliveryOutcome.DELIVERED, null);
        deliveryRepository.record(eventId, 2, "http://target.test/hook", 503, DeliveryOutcome.RETRY, "HTTP 503");

        ResponseEntity<String> response = rest.getForEntity(
                "/v1/events/" + eventId + "/deliveries", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode attempts = readJson(response.getBody());
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0).path("outcome").asText()).isEqualTo("DELIVERED");
        assertThat(attempts.get(0).path("statusCode").asInt()).isEqualTo(200);
        assertThat(attempts.get(0).path("error").isMissingNode()).isTrue();
        assertThat(attempts.get(1).path("outcome").asText()).isEqualTo("RETRY");
        assertThat(attempts.get(1).path("error").asText()).isEqualTo("HTTP 503");
    }

    @Test
    void aReplayedEventMovesFromFailedToDelivered() {
        ResponseEntity<IngestResponse> created = post("evt-replay", BODY);
        UUID eventId = created.getBody().id();
        eventRepository.markFailed(eventId);
        assertThat(eventRepository.findById(eventId).orElseThrow().status())
                .isEqualTo(EventStatus.FAILED);

        eventRepository.markDelivered(eventId);

        assertThat(eventRepository.findById(eventId).orElseThrow().status())
                .isEqualTo(EventStatus.DELIVERED);
    }

    private ResponseEntity<IngestResponse> post(String eventId, String body) {
        return rest.postForEntity("/v1/events", signedRequest(eventId, body), IngestResponse.class);
    }

    private HttpEntity<byte[]> signedRequest(String eventId, String body) {
        long timestamp = Instant.now().getEpochSecond();
        return signedRequest(
                eventId,
                body,
                timestamp,
                Hmac.signHex(SECRET, Long.toString(timestamp), body.getBytes(StandardCharsets.UTF_8))
        );
    }

    private HttpEntity<byte[]> signedRequest(String eventId, String body, long timestamp, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Hook-Source", "demo");
        headers.set("X-Hook-Id", eventId);
        headers.set("X-Hook-Type", "test.event");
        headers.set("X-Hook-Timestamp", Long.toString(timestamp));
        headers.set("X-Hook-Signature", signature);
        return new HttpEntity<>(body.getBytes(StandardCharsets.UTF_8), headers);
    }

    private long count(String sql) {
        return jdbc.sql(sql).query((rs, rowNum) -> rs.getLong(1)).single();
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON in test response", e);
        }
    }
}
