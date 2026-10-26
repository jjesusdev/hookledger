package com.jjesusdev.hookledger.ingest;

import com.jjesusdev.hookledger.event.EventRepository;
import com.jjesusdev.hookledger.event.EventView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class IngestController {

    private final IngestService ingestService;
    private final EventRepository eventRepository;

    public IngestController(IngestService ingestService, EventRepository eventRepository) {
        this.ingestService = ingestService;
        this.eventRepository = eventRepository;
    }

    @PostMapping(path = "/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    public IngestResponse ingest(
            @RequestHeader("X-Hook-Source") String source,
            @RequestHeader("X-Hook-Id") String eventId,
            @RequestHeader("X-Hook-Timestamp") String timestamp,
            @RequestHeader("X-Hook-Signature") String signature,
            @RequestHeader(value = "X-Hook-Type", required = false) String eventType,
            @RequestHeader(value = "User-Agent", required = false) String userAgent,
            @RequestBody byte[] rawBody) {

        IngestResult result = ingestService.ingest(
                source,
                eventId,
                eventType,
                timestamp,
                signature,
                rawBody,
                Map.of("userAgent", userAgent == null ? "" : userAgent)
        );
        return new IngestResponse(result.id(), result.status().name(), result.duplicate());
    }

    @GetMapping("/events/{id}")
    public EventView get(@PathVariable UUID id) {
        return eventRepository.findById(id)
                .map(EventView::from)
                .orElseThrow(() -> new WebhookRejectedException(404, "EVENT_NOT_FOUND", "Event not found"));
    }
}
