package com.jjesusdev.hookledger.delivery;

import com.jjesusdev.hookledger.config.HookledgerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplayServiceTest {

    private static final String MAIN = "http://localhost:4566/000000000000/hookledger-events";
    private static final String DLQ = MAIN + "-dlq";

    @Test
    void movesMessagesFromTheDlqToTheMainQueue() {
        SqsClient sqs = mock(SqsClient.class);
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder()
                        .messages(Message.builder()
                                .messageId("m1")
                                .receiptHandle("rh-1")
                                .body("{\"id\":\"11111111-1111-1111-1111-111111111111\"}")
                                .build())
                        .build());
        ReplayService service = new ReplayService(sqs, properties(), new SimpleMeterRegistry());

        int replayed = service.replay(10);

        assertThat(replayed).isEqualTo(1);
        verify(sqs).sendMessage(any(SendMessageRequest.class));
        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void requiresBothQueues() {
        SqsClient sqs = mock(SqsClient.class);
        HookledgerProperties properties = new HookledgerProperties(
                null, null, null, "sqs", null,
                new HookledgerProperties.Sqs(MAIN, "", "us-east-1", ""),
                null, null, null);
        ReplayService service = new ReplayService(sqs, properties, new SimpleMeterRegistry());

        assertThatThrownBy(() -> service.replay(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DLQ");
    }

    private static HookledgerProperties properties() {
        return new HookledgerProperties(
                null, null, null, "sqs", null,
                new HookledgerProperties.Sqs(MAIN, DLQ, "us-east-1", ""),
                null, null, null);
    }
}
