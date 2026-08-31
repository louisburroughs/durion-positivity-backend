package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.security.RolePersonaChangedV1;
import com.positivity.securityservice.internal.config.OutboxEventWriter;
import com.positivity.securityservice.internal.entity.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Issue #1613: role persona facts on {@code security.events.v1}.
 *
 * <p>The emitter must never be able to fail a role write — the consumer's scheduled pull is the
 * fallback — so the Kafka-disabled path matters as much as the happy one.
 */
@DisplayName("RolePersonaEventEmitter (#1613)")
class RolePersonaEventEmitterTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID ROLE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static Role role() {
        Role role = new Role();
        role.setId(ROLE_ID);
        role.setName("SHOP_MANAGER");
        role.setDescription("Branch operations lead");
        role.setPersonaTitle("shop manager");
        role.setPersonaFocus("branch operations and queue control");
        role.setPersonaTone("decisive and operational");
        role.setMcpPersonaRank((short) 35);
        role.setMcpPersonaEligible(true);
        role.setCreatedAt(Instant.parse("2026-08-01T00:00:00Z"));
        return role;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<OutboxEventWriter> providerFor(OutboxEventWriter writer) {
        ObjectProvider<OutboxEventWriter> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(writer);
        return provider;
    }

    @Test
    @DisplayName("publishes the role's current persona state to the configured topic")
    void publishesCurrentPersonaState() {
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        var emitter = new RolePersonaEventEmitter(TEST_CLOCK, providerFor(writer), "security.events.v1");
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("admin-user", "n/a", java.util.List.of()));

        emitter.rolePersonaChanged(role());

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("security.events.v1"), captor.capture());

        DomainEventEnvelope<?> envelope = captor.getValue();
        assertThat(envelope.eventType()).isEqualTo(RolePersonaChangedV1.EVENT_TYPE);
        assertThat(envelope.aggregateId()).isEqualTo(ROLE_ID);
        assertThat(envelope.sourceService()).isEqualTo("pos-security-service");
        assertThat(envelope.actor()).isEqualTo("admin-user");

        // The fact carries current state, not a delta — that is what makes it safe to reprocess.
        RolePersonaChangedV1 payload = (RolePersonaChangedV1) envelope.payload();
        assertThat(payload.name()).isEqualTo("SHOP_MANAGER");
        assertThat(payload.personaTitle()).isEqualTo("shop manager");
        assertThat(payload.personaFocus()).isEqualTo("branch operations and queue control");
        assertThat(payload.personaTone()).isEqualTo("decisive and operational");
        assertThat(payload.mcpPersonaRank()).isEqualTo((short) 35);
        assertThat(payload.mcpPersonaEligible()).isTrue();
    }

    @Test
    @DisplayName("is a no-op when Kafka is disabled, so a role write never depends on the broker")
    void noOpWhenKafkaDisabled() {
        ObjectProvider<OutboxEventWriter> absent = providerFor(null);
        var emitter = new RolePersonaEventEmitter(TEST_CLOCK, absent, "security.events.v1");

        emitter.rolePersonaChanged(role());

        verify(absent).getIfAvailable();
    }

    @Test
    @DisplayName("an ineligible role still emits, so the consumer learns it is excluded by design")
    void ineligibleRoleStillEmits() {
        // Without this the consumer could never distinguish "excluded" from "never heard of it",
        // which is the metric ambiguity decision 2 exists to remove.
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        var emitter = new RolePersonaEventEmitter(TEST_CLOCK, providerFor(writer), "security.events.v1");
        Role customer = role();
        customer.setName("CUSTOMER");
        customer.setMcpPersonaEligible(false);

        emitter.rolePersonaChanged(customer);

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(any(), captor.capture());
        assertThat(((RolePersonaChangedV1) captor.getValue().payload()).mcpPersonaEligible())
                .isFalse();
    }

    @Test
    @DisplayName("aggregateVersion advances with the role's last write")
    void aggregateVersionTracksLastWrite() {
        // roles has no @Version column, so the last-write timestamp stands in for the monotonic
        // per-aggregate sequence a consumer uses for gap and staleness detection.
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        var emitter = new RolePersonaEventEmitter(TEST_CLOCK, providerFor(writer), "security.events.v1");
        Role edited = role();
        Instant editedAt = Instant.parse("2026-08-31T11:59:00Z");
        edited.setLastModifiedAt(editedAt);

        emitter.rolePersonaChanged(edited);

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(any(), captor.capture());
        assertThat(captor.getValue().aggregateVersion()).isEqualTo(editedAt.toEpochMilli());
    }

    @Test
    @DisplayName("a never-modified role falls back to its creation stamp")
    void aggregateVersionFallsBackToCreation() {
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        var emitter = new RolePersonaEventEmitter(TEST_CLOCK, providerFor(writer), "security.events.v1");

        emitter.rolePersonaChanged(role());

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(any(), captor.capture());
        assertThat(captor.getValue().aggregateVersion())
                .isEqualTo(Instant.parse("2026-08-01T00:00:00Z").toEpochMilli());
    }

    @Test
    @DisplayName("an unauthenticated write records the system actor rather than failing")
    void unauthenticatedWriteRecordsSystemActor() {
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        var emitter = new RolePersonaEventEmitter(TEST_CLOCK, providerFor(writer), "security.events.v1");

        emitter.rolePersonaChanged(role());

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(any(), captor.capture());
        assertThat(captor.getValue().actor()).isEqualTo("system");
        verify(writer, never()).publishRaw(any(), any(), any());
    }
}
