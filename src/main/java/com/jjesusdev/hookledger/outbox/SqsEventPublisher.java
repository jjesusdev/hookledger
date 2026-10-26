package com.jjesusdev.hookledger.outbox;

import com.jjesusdev.hookledger.config.HookledgerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Map;

@Component
@ConditionalOnProperty(name = "hookledger.publisher", havingValue = "sqs")
public class SqsEventPublisher implements EventPublisher {

    private final SqsClient sqs;
    private final String queueUrl;

    public SqsEventPublisher(SqsClient sqs, HookledgerProperties properties) {
        this.sqs = sqs;
        this.queueUrl = properties.sqs().queueUrl();
        if (queueUrl.isBlank()) {
            throw new IllegalStateException("hookledger.sqs.queue-url is required when publisher=sqs");
        }
    }

    @Override
    public void publish(OutboxRow row) {
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(row.payload())
                .messageAttributes(Map.of(
                        "eventId", attribute(row.eventId().toString()),
                        "outboxAttempt", attribute(Integer.toString(row.attempts()))
                ))
                .build());
    }

    private static MessageAttributeValue attribute(String value) {
        return MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(value)
                .build();
    }
}
