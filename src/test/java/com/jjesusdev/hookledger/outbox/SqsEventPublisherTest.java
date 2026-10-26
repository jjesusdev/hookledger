package com.jjesusdev.hookledger.outbox;

import com.jjesusdev.hookledger.config.HookledgerProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SqsEventPublisherTest {

    private static final String QUEUE_URL = "http://localhost:4566/000000000000/hookledger-events";

    @Test
    void sendsTheOutboxPayloadWithCorrelationAttributes() {
        SqsClient sqs = mock(SqsClient.class);
        SqsEventPublisher publisher = new SqsEventPublisher(sqs, properties(QUEUE_URL));
        UUID eventId = UUID.randomUUID();
        String payload = "{\"id\":\"" + eventId + "\",\"source\":\"demo\"}";

        publisher.publish(new OutboxRow(42, eventId, payload, 0, Instant.parse("2026-10-26T12:00:00Z")));

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(captor.capture());
        SendMessageRequest request = captor.getValue();
        assertThat(request.queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(request.messageBody()).isEqualTo(payload);
        Map<String, MessageAttributeValue> attributes = request.messageAttributes();
        assertThat(attributes.get("eventId").stringValue()).isEqualTo(eventId.toString());
        assertThat(attributes.get("outboxAttempt").stringValue()).isEqualTo("0");
    }

    @Test
    void failsFastWhenTheQueueUrlIsMissing() {
        SqsClient sqs = mock(SqsClient.class);

        assertThatThrownBy(() -> new SqsEventPublisher(sqs, properties("")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("queue-url");
    }

    private static HookledgerProperties properties(String queueUrl) {
        return new HookledgerProperties(
                null,
                null,
                null,
                "sqs",
                null,
                new HookledgerProperties.Sqs(queueUrl, "", "us-east-1", "http://localhost:4566"),
                null,
                null,
                null
        );
    }
}
