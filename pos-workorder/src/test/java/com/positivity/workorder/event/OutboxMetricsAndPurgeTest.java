package com.positivity.workorder.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.config.OutboxMetrics;
import com.positivity.workorder.internal.config.OutboxPurgeJob;
import com.positivity.workorder.internal.entity.OutboxEvent;
import com.positivity.workorder.internal.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

class OutboxMetricsAndPurgeTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-08T12:00:00Z"), ZoneOffset.UTC);

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);

    @Test
    @DisplayName("Gauges report pending count and oldest unpublished age (0 when drained)")
    void gaugesReportBacklogAndHeadAge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(provider.getIfAvailable()).thenReturn(registry);
        when(repository.countByPublishedAtIsNull()).thenReturn(7L);
        when(repository.findFirstByPublishedAtIsNullOrderByIdAsc())
                .thenReturn(Optional.of(OutboxEvent.builder()
                        .createdAt(Instant.parse("2026-07-08T11:55:00Z"))
                        .build()));

        new OutboxMetrics(repository, TEST_CLOCK, provider);

        assertThat(registry.get("workorder.outbox.pending").gauge().value()).isEqualTo(7.0);
        assertThat(registry.get("workorder.outbox.oldest.age.seconds").gauge().value())
                .isEqualTo(300.0);

        when(repository.findFirstByPublishedAtIsNullOrderByIdAsc()).thenReturn(Optional.empty());
        assertThat(registry.get("workorder.outbox.oldest.age.seconds").gauge().value())
                .isZero();
    }

    @Test
    @DisplayName("Purge deletes published rows older than the retention cutoff only")
    void purgeUsesRetentionCutoff() {
        when(repository.purgePublishedBefore(any())).thenReturn(3);
        OutboxPurgeJob job = new OutboxPurgeJob(repository, TEST_CLOCK);
        ReflectionTestUtils.setField(job, "retentionDays", 90L);

        job.purge();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).purgePublishedBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(Instant.parse("2026-04-09T12:00:00Z"));
    }
}
