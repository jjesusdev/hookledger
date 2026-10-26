package com.jjesusdev.hookledger.delivery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryMetricsTest {

    @Test
    void registersTheCountersEvenWhenTheWorkerIsDisabled() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeliveryMetrics metrics = new DeliveryMetrics(registry);

        metrics.delivered();
        metrics.retry();
        metrics.failed();

        assertThat(count(registry, "delivered")).isEqualTo(1);
        assertThat(count(registry, "retry")).isEqualTo(1);
        assertThat(count(registry, "failed")).isEqualTo(1);
    }

    private static double count(SimpleMeterRegistry registry, String result) {
        return registry.get("hookledger.delivery.total").tag("result", result).counter().count();
    }
}
