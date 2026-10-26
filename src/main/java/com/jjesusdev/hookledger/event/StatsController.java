package com.jjesusdev.hookledger.event;

import com.jjesusdev.hookledger.delivery.DeliveryOutcome;
import com.jjesusdev.hookledger.delivery.DeliveryRepository;
import com.jjesusdev.hookledger.outbox.OutboxRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/v1")
public class StatsController {

    private final EventRepository eventRepository;
    private final OutboxRepository outboxRepository;
    private final DeliveryRepository deliveryRepository;
    private final Clock clock;

    public StatsController(EventRepository eventRepository,
                           OutboxRepository outboxRepository,
                           DeliveryRepository deliveryRepository,
                           Clock clock) {
        this.eventRepository = eventRepository;
        this.outboxRepository = outboxRepository;
        this.deliveryRepository = deliveryRepository;
        this.clock = clock;
    }

    @GetMapping("/stats")
    public StatsResponse stats() {
        Map<String, Long> counts = eventRepository.countByStatus();
        long received = counts.getOrDefault("RECEIVED", 0L);
        long published = counts.getOrDefault("PUBLISHED", 0L);
        long delivered = counts.getOrDefault("DELIVERED", 0L);
        long failed = counts.getOrDefault("FAILED", 0L);
        long total = received + published + delivered + failed;

        Instant oldest = outboxRepository.oldestPendingCreatedAt();
        Long oldestAgeSeconds = oldest == null
                ? null
                : Math.max(0, Duration.between(oldest, clock.instant()).toSeconds());

        return new StatsResponse(
                total,
                received,
                published,
                delivered,
                failed,
                outboxRepository.countPending(),
                oldestAgeSeconds,
                deliveryRepository.countByOutcome(DeliveryOutcome.DELIVERED),
                deliveryRepository.countByOutcome(DeliveryOutcome.RETRY),
                deliveryRepository.countByOutcome(DeliveryOutcome.FAILED)
        );
    }

    public record StatsResponse(
            long total,
            long received,
            long published,
            long delivered,
            long failed,
            long outboxPending,
            Long outboxOldestAgeSeconds,
            long deliveryDelivered,
            long deliveryRetries,
            long deliveryFailed
    ) {
    }
}
