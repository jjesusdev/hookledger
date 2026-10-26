package com.jjesusdev.hookledger.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxHealthIndicatorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void reportsDownInsteadOfThrowingWhenTheDatabaseIsGone() {
        OutboxRepository repository = mock(OutboxRepository.class);
        when(repository.countPending())
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        Health health = new OutboxHealthIndicator(repository, CLOCK).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("error", "DataAccessResourceFailureException");
    }

    @Test
    void reportsTheBacklogAndTheAgeOfTheOldestRow() {
        OutboxRepository repository = mock(OutboxRepository.class);
        when(repository.countPending()).thenReturn(7L);
        when(repository.oldestPendingCreatedAt()).thenReturn(Instant.parse("2026-10-26T11:59:30Z"));

        Health health = new OutboxHealthIndicator(repository, CLOCK).health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails())
                .containsEntry("pending", 7L)
                .containsEntry("oldestAgeSeconds", 30L);
    }
}
