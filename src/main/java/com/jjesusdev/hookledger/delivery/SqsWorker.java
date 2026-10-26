package com.jjesusdev.hookledger.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjesusdev.hookledger.config.HookledgerProperties;
import com.jjesusdev.hookledger.event.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "hookledger.worker.enabled", havingValue = "true")
public class SqsWorker {

    private static final Logger log = LoggerFactory.getLogger(SqsWorker.class);

    private final SqsClient sqs;
    private final HookledgerProperties properties;
    private final DeliveryService deliveryService;
    private final DeliveryRepository deliveryRepository;
    private final EventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final String queueUrl;
    private final DeliveryMetrics metrics;

    public SqsWorker(SqsClient sqs,
                     HookledgerProperties properties,
                     DeliveryService deliveryService,
                     DeliveryRepository deliveryRepository,
                     EventRepository eventRepository,
                     ObjectMapper objectMapper,
                     DeliveryMetrics metrics) {
        this.sqs = sqs;
        this.properties = properties;
        this.deliveryService = deliveryService;
        this.deliveryRepository = deliveryRepository;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.queueUrl = properties.sqs().queueUrl();
        if (queueUrl.isBlank()) {
            throw new IllegalStateException("hookledger.sqs.queue-url is required when worker.enabled=true");
        }
    }

    @Scheduled(fixedDelayString = "${hookledger.worker.poll-interval-ms:1000}")
    public void poll() {
        List<Message> messages;
        try {
            messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .maxNumberOfMessages(properties.worker().maxMessages())
                            .waitTimeSeconds(properties.worker().waitTimeSeconds())
                            .visibilityTimeout(properties.worker().visibilityTimeoutSeconds())
                            .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                            .messageAttributeNames("All")
                            .build())
                    .messages();
        } catch (RuntimeException e) {
            log.error("could not receive from SQS; will retry", e);
            return;
        }

        for (Message message : messages) {
            try {
                process(message);
            } catch (RuntimeException e) {
                log.error("unexpected error processing message {}", message.messageId(), e);
                retryLater(message, receiveCount(message), "unexpected worker error");
            }
        }
    }

    void process(Message message) {
        int attempt = receiveCount(message);
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message.body());
        } catch (Exception e) {
            log.warn("discarding malformed message {} after attempt {}", message.messageId(), attempt);
            retryLater(message, attempt, "malformed envelope");
            return;
        }

        UUID eventId;
        try {
            eventId = UUID.fromString(envelope.path("id").asText());
        } catch (IllegalArgumentException e) {
            log.warn("message {} has no valid event id", message.messageId());
            retryLater(message, attempt, "missing event id");
            return;
        }

        String source = envelope.path("source").asText("unknown");
        String eventType = envelope.path("type").asText("unknown");
        JsonNode payload = envelope.path("payload");
        HookledgerProperties.Target target = properties.targets().get(source);

        DeliveryService.DeliveryResult result = deliveryService.deliver(
                target, source, eventId, eventType, payload, attempt);

        if (result.successful()) {
            deliveryRepository.record(eventId, target.url(), result.statusCode(),
                    DeliveryOutcome.DELIVERED, null);
            eventRepository.markDelivered(eventId);
            delete(message);
            metrics.delivered();
            log.debug("delivered event {} to {} (SQS receive {})", eventId, target.url(), attempt);
            return;
        }

        boolean lastAttempt = attempt >= properties.worker().maxReceives();
        DeliveryOutcome outcome = lastAttempt ? DeliveryOutcome.FAILED : DeliveryOutcome.RETRY;
        deliveryRepository.record(eventId, target == null ? "" : target.url(),
                result.statusCode() == 0 ? null : result.statusCode(), outcome, result.error());

        if (lastAttempt) {
            eventRepository.markFailed(eventId);
            metrics.failed();
            log.warn("event {} failed after {} SQS receives; leaving it for the DLQ: {}",
                    eventId, attempt, result.error());
        } else {
            metrics.retry();
            retryLater(message, attempt, result.error());
        }
    }

    private void retryLater(Message message, int attempt, String reason) {
        if (attempt >= properties.worker().maxReceives()) {
            log.warn("message {} exhausted {} attempts: {}", message.messageId(), attempt, reason);
            return;
        }
        int delay = backoffSeconds(attempt);
        try {
            sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .visibilityTimeout(delay)
                    .build());
        } catch (RuntimeException e) {
            log.error("could not change visibility for message {}", message.messageId(), e);
        }
    }

    private void delete(Message message) {
        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build());
    }

    private int backoffSeconds(int attempt) {
        int exponent = Math.min(Math.max(0, attempt - 1), 10);
        long exponential = (long) properties.worker().backoffBaseSeconds() << exponent;
        long capped = Math.min(exponential, properties.worker().backoffMaxSeconds());
        // The delay is applied after the delivery attempt ended, so it does not need to cover
        // processing time. SQS rejects a negative visibility timeout; 1s is the safe floor.
        return (int) Math.max(capped, 1);
    }

    private static int receiveCount(Message message) {
        String value = message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
        if (value == null) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
