package com.positivity.platformsender.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.sender.SenderMessageOutcomeV1;
import com.positivity.platformsender.internal.config.OutboxEventWriter;
import com.positivity.platformsender.internal.entity.ProcessedEvent;
import com.positivity.platformsender.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

@DisplayName("OutcomeRelay — one outbox row per provider event, under the tagged tenant")
class OutcomeRelayTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-000000000002");
    private static final UUID MESSAGE_ID = UUID.fromString("01990000-0000-7000-8000-0000000000c1");
    private static final Instant OCCURRED = Instant.parse("2026-10-03T12:00:02Z");

    private final OutboxEventWriter writer = mock(OutboxEventWriter.class);
    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final OutcomeRelay relay = new OutcomeRelay(
            writer,
            processed,
            Clock.fixed(Instant.parse("2026-10-03T12:00:05Z"), ZoneOffset.UTC),
            mock(PlatformTransactionManager.class),
            "sender.outcomes.v1");

    private static ProviderOutcomeMapper.Mapped bounce() {
        return new ProviderOutcomeMapper.Mapped(
                "sns-1",
                TENANT,
                SenderMessageOutcomeV1.EVENT_TYPE_BOUNCED,
                new SenderMessageOutcomeV1(
                        MESSAGE_ID, "EMAIL", "ses-1", OCCURRED, "Permanent: General", true, "ada@example.com"));
    }

    @AfterEach
    void unbind() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("queues the envelope keyed by providerMessageId, bound to the tagged tenant, and marks the event")
    void queuesUnderTenant() {
        AtomicReference<UUID> boundDuringWrite = new AtomicReference<>();
        doAnswer(_ -> {
                    boundDuringWrite.set(TenantContext.current().orElse(null));
                    return null;
                })
                .when(writer)
                .publish(any(), any(), any());

        assertThat(relay.relay("sns-1", bounce())).isTrue();

        assertThat(boundDuringWrite.get()).isEqualTo(TENANT);
        assertThat(TenantContext.current())
                .as("the binding ends with the relay")
                .isEmpty();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<DomainEventEnvelope<SenderMessageOutcomeV1>> envelope = ArgumentCaptor.captor();
        verify(writer).publish(eq("sender.outcomes.v1"), eq("ses-1"), envelope.capture());
        assertThat(envelope.getValue().eventType()).isEqualTo(SenderMessageOutcomeV1.EVENT_TYPE_BOUNCED);
        assertThat(envelope.getValue().aggregateId()).isEqualTo(MESSAGE_ID);
        assertThat(envelope.getValue().aggregateVersion()).isEqualTo(OCCURRED.toEpochMilli());
        assertThat(envelope.getValue().sourceService()).isEqualTo("pos-platform-sender");
        assertThat(envelope.getValue().payload().address()).isEqualTo("ada@example.com");
        ArgumentCaptor<ProcessedEvent> mark = ArgumentCaptor.captor();
        verify(processed).save(mark.capture());
        assertThat(mark.getValue().getEventId()).isEqualTo("sns-1");
        assertThat(mark.getValue().getOwner()).isEqualTo(OutcomeRelay.OWNER);
    }

    @Test
    @DisplayName("a provider event already relayed is not queued again")
    void dedupes() {
        when(processed.existsById("sns-1")).thenReturn(true);

        assertThat(relay.relay("sns-1", bounce())).isFalse();

        verify(writer, never()).publish(any(), any(), any());
        verify(processed, never()).save(any());
    }
}
