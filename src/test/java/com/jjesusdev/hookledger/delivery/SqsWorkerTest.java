package com.jjesusdev.hookledger.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjesusdev.hookledger.config.HookledgerProperties;
import com.jjesusdev.hookledger.event.EventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SqsWorkerTest {

    private static final String QUEUE_URL = "http://localhost:4566/000000000000/hookledger-events";
    private static final String TARGET_URL = "http://target.test/v1/demo/receive";
    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private SqsClient sqs;
    private DeliveryService deliveryService;
    private DeliveryRepository deliveryRepository;
    private EventRepository eventRepository;
    private SqsWorker worker;

    @BeforeEach
    void setUp() {
        sqs = mock(SqsClient.class);
        deliveryService = mock(DeliveryService.class);
        deliveryRepository = mock(DeliveryRepository.class);
        eventRepository = mock(EventRepository.class);
        HookledgerProperties properties = new HookledgerProperties(
                null,
                null,
                null,
                "log",
                null,
                new HookledgerProperties.Sqs(QUEUE_URL, QUEUE_URL + "-dlq", "us-east-1", ""),
                new HookledgerProperties.Worker(true, 1000, 0, 10, 3, 30, 2, 60),
                Map.of("demo", new HookledgerProperties.Target(TARGET_URL, "secret")),
                null
        );
        worker = new SqsWorker(sqs, properties, deliveryService, deliveryRepository,
                eventRepository, new ObjectMapper(), new SimpleMeterRegistry());
    }

    @Test
    void deletesTheMessageAndMarksTheEventDeliveredOnSuccess() {
        when(deliveryService.deliver(any(), eq("demo"), eq(EVENT_ID), eq("test.event"), any(), eq(1)))
                .thenReturn(DeliveryService.DeliveryResult.success(200));

        worker.process(message("1"));

        ArgumentCaptor<DeleteMessageRequest> captor = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(sqs).deleteMessage(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(captor.getValue().receiptHandle()).isEqualTo("receipt-1");
        verify(deliveryRepository).record(EVENT_ID, 1, TARGET_URL, 200, DeliveryOutcome.DELIVERED, null);
        verify(eventRepository).markDelivered(EVENT_ID);
    }

    @Test
    void retriesWithVisibilityBackoffBeforeDeleting() {
        when(deliveryService.deliver(any(), any(), any(), any(), any(), eq(1)))
                .thenReturn(DeliveryService.DeliveryResult.failure(503, "target returned HTTP 503"));

        worker.process(message("1"));

        verify(deliveryRepository).record(EVENT_ID, 1, TARGET_URL, 503, DeliveryOutcome.RETRY,
                "target returned HTTP 503");
        ArgumentCaptor<ChangeMessageVisibilityRequest> captor =
                ArgumentCaptor.forClass(ChangeMessageVisibilityRequest.class);
        verify(sqs).changeMessageVisibility(captor.capture());
        assertThat(captor.getValue().visibilityTimeout()).isEqualTo(30);
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
        verify(eventRepository, never()).markFailed(EVENT_ID);
    }

    @Test
    void marksTheEventFailedAndLeavesTheLastAttemptForTheDlq() {
        when(deliveryService.deliver(any(), any(), any(), any(), any(), eq(3)))
                .thenReturn(DeliveryService.DeliveryResult.failure(500, "boom"));

        worker.process(message("3"));

        verify(deliveryRepository).record(EVENT_ID, 3, TARGET_URL, 500, DeliveryOutcome.FAILED, "boom");
        verify(eventRepository).markFailed(EVENT_ID);
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
        verify(sqs, never()).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
    }

    @Test
    void malformedMessagesAreRetriedInsteadOfCrashingThePoller() {
        worker.process(Message.builder()
                .messageId("bad")
                .receiptHandle("receipt-bad")
                .body("not json")
                .attributes(Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "1"))
                .build());

        verify(sqs).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
        verifyNoInteractions(deliveryRepository);
    }

    private static Message message(String receiveCount) {
        return Message.builder()
                .messageId("message-" + receiveCount)
                .receiptHandle("receipt-" + receiveCount)
                .body("""
                        {"id":"%s","source":"demo","type":"test.event","payload":{"n":1}}
                        """.formatted(EVENT_ID))
                .attributes(Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, receiveCount))
                .build();
    }
}
