package com.jjesusdev.hookledger.delivery;

import com.jjesusdev.hookledger.config.HookledgerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.List;

@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    private final SqsClient sqs;
    private final HookledgerProperties properties;
    private final Counter replayedCounter;

    public ReplayService(SqsClient sqs, HookledgerProperties properties, MeterRegistry meterRegistry) {
        this.sqs = sqs;
        this.properties = properties;
        this.replayedCounter = meterRegistry.counter("hookledger.replay.total");
    }

    /**
     * Moves up to {@code max} messages from the DLQ back to the main queue. It is at-least-once:
     * a crash after send and before delete can replay the same message again.
     */
    public int replay(int max) {
        String mainQueue = properties.sqs().queueUrl();
        String dlq = properties.sqs().dlqUrl();
        if (mainQueue.isBlank() || dlq.isBlank()) {
            throw new IllegalStateException("SQS main and DLQ URLs are required for replay");
        }

        List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(dlq)
                        .maxNumberOfMessages(max)
                        .waitTimeSeconds(0)
                        .visibilityTimeout(properties.worker().visibilityTimeoutSeconds())
                        .build())
                .messages();

        int replayed = 0;
        for (Message message : messages) {
            sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(mainQueue)
                    .messageBody(message.body())
                    .build());
            sqs.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(dlq)
                    .receiptHandle(message.receiptHandle())
                    .build());
            replayed++;
        }
        if (replayed > 0) {
            replayedCounter.increment(replayed);
            log.info("replayed {} messages from the DLQ", replayed);
        }
        return replayed;
    }
}
