package com.jjesusdev.hookledger.outbox;

public interface EventPublisher {

    void publish(OutboxRow row);
}
