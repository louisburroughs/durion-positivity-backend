package com.positivity.platformsender.internal.config;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.sender.SenderMessageOutcomeV1;
import com.positivity.platformsender.internal.entity.OutboxEvent;
import com.positivity.platformsender.internal.repository.OutboxEventRepository;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link OutboxEventWriter}: the row carries the caller's record key (FI-2 §2 keys the outcome
 * topic by {@code providerMessageId}), the whole envelope stamped with the bound tenant, and a
 * serialization failure fails the transaction instead of queueing an unpublishable row.
 */
@DisplayName("OutboxEventWriter — transactional outbox row contract")
class OutboxEventWriterTest {

    private static final UUID MESSAGE_ID = UUID.fromString("01990000-0000-7000-8000-0000000000c1");
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private OutboxEventWriter writer;

    /** Resolves the alpha default tenant, as an unbound path does at runtime (ADR-0062). */
    private static TenantResolver tenantResolver() {
        TenancyProperties tenancy = new TenancyProperties();
        tenancy.setDefaultTenantId(TENANT_A);
        return new TenantResolver(tenancy);
    }

    @BeforeEach
    void setUp() {
        writer = new OutboxEventWriter(clock, new ObjectMapper(), repository, tenantResolver());
    }

    private DomainEventEnvelope<SenderMessageOutcomeV1> envelope() {
        return DomainEventEnvelope.of(
                SenderMessageOutcomeV1.EVENT_TYPE_DELIVERED,
                SenderMessageOutcomeV1.SCHEMA_VERSION,
                MESSAGE_ID,
                NOW.toEpochMilli(),
                "pos-platform-sender",
                null,
                "pos-platform-sender",
                new SenderMessageOutcomeV1(MESSAGE_ID, "EMAIL", "ses-1", NOW, null, null, null),
                clock);
    }

    @Test
    @DisplayName("keys the row on the caller's key and stores the tenant-stamped envelope")
    void writesRow() {
        writer.publish("sender.outcomes.v1", "ses-1", envelope());

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        OutboxEvent row = saved.getValue();
        assertThat(row.getTenantId()).isEqualTo(TENANT_A);
        assertThat(row.getTopic()).isEqualTo("sender.outcomes.v1");
        assertThat(row.getRecordKey()).isEqualTo("ses-1");
        assertThat(row.getCreatedAt()).isEqualTo(NOW);
        assertThat(new ObjectMapper()
                        .readTree(row.getPayload())
                        .path("tenantId")
                        .stringValue())
                .isEqualTo(TENANT_A.toString());
        assertThat(new ObjectMapper()
                        .readTree(row.getPayload())
                        .path("payload")
                        .path("providerMessageId")
                        .stringValue())
                .isEqualTo("ses-1");
        assertThat(row.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("fails the transaction rather than queueing an envelope it could not serialize")
    void serializationFailureIsFatal() {
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsString(any())).thenThrow(new IllegalStateException("boom"));
        OutboxEventWriter failingWriter = new OutboxEventWriter(clock, failing, repository, tenantResolver());

        assertThatThrownBy(() -> failingWriter.publish("sender.outcomes.v1", "ses-1", envelope()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SenderMessageOutcomeV1.EVENT_TYPE_DELIVERED);
        verify(repository, never()).save(any());
    }
}
