package com.jjesusdev.hookledger.delivery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Registered even when the worker is disabled, so dashboards and alerts do not lose the series when
 * the API and the worker run as separate processes.
 */
@Component
public class DeliveryMetrics {

    private final Counter delivered;
    private final Counter retry;
    private final Counter failed;

    public DeliveryMetrics(MeterRegistry meterRegistry) {
        this.delivered = meterRegistry.counter("hookledger.delivery.total", "result", "delivered");
        this.retry = meterRegistry.counter("hookledger.delivery.total", "result", "retry");
        this.failed = meterRegistry.counter("hookledger.delivery.total", "result", "failed");
    }

    public void delivered() {
        delivered.increment();
    }

    public void retry() {
        retry.increment();
    }

    public void failed() {
        failed.increment();
    }
}
