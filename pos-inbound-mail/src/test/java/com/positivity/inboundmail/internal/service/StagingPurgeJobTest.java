package com.positivity.inboundmail.internal.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.positivity.inboundmail.internal.config.InboundMailProperties;
import com.positivity.tenancy.TenantIterator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class StagingPurgeJobTest {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void purgesOlderThanTheTtlOncePerTenant() {
        AttachmentStaging staging = mock(AttachmentStaging.class);
        TenantIterator iterator = mock(TenantIterator.class);
        doAnswer(invocation -> {
                    Consumer<UUID> work = invocation.getArgument(0);
                    work.accept(UUID.randomUUID());
                    work.accept(UUID.randomUUID());
                    return 2;
                })
                .when(iterator)
                .forEachActiveTenant(any(Consumer.class));
        StagingPurgeJob job = new StagingPurgeJob(
                staging,
                iterator,
                new InboundMailProperties("s", "none", "mail.example.com", Duration.ofDays(7), "x", 1L, 200, 2),
                Clock.fixed(NOW, ZoneOffset.UTC));

        job.purgeExpiredAttachments();

        verify(staging, org.mockito.Mockito.times(2)).purgeStagedBefore(NOW.minus(Duration.ofDays(7)));
    }
}
