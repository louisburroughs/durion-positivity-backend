package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.security.RoleAssignmentChangedV1;
import com.positivity.securityservice.internal.config.OutboxEventWriter;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.enums.LocationScope;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
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
 * Issue #2160: role-assignment facts on {@code security.events.v1}.
 *
 * <p>Same failure posture as {@link RolePersonaEventEmitterTest}: the emitter must never be able
 * to fail the assignment write it rides along with, so the Kafka-disabled path matters as much as
 * the happy one.
 */
@DisplayName("RoleAssignmentEventEmitter (#2160)")
class RoleAssignmentEventEmitterTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-22T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID ASSIGNMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final UUID ROLE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000dd");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static RoleAssignment assignment() {
        return assignment(LocationScope.ALL);
    }

    private static RoleAssignment assignment(LocationScope locationScope) {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("jane.doe");

        Role role = new Role();
        role.setId(ROLE_ID);
        role.setName("SHOP_MANAGER");
        role.setLocationScope(locationScope);

        RoleAssignment assignment = new RoleAssignment();
        assignment.setId(ASSIGNMENT_ID);
        assignment.setUser(user);
        assignment.setRole(role);
        assignment.setEffectiveStartDate(LocalDateTime.parse("2026-09-01T00:00:00"));
        assignment.setCreatedAt(Instant.parse("2026-09-01T00:00:00Z"));
        return assignment;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<OutboxEventWriter> providerFor(OutboxEventWriter writer) {
        ObjectProvider<OutboxEventWriter> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(writer);
        return provider;
    }

    @Test
    @DisplayName("publishes a fact carrying username, not only userId, on grant")
    void publishesFactOnGrant() {
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        var emitter = new RoleAssignmentEventEmitter(TEST_CLOCK, providerFor(writer), "security.events.v1");
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("admin-user", "n/a", java.util.List.of()));
        RoleAssignment assignment = assignment();

        emitter.roleAssignmentChanged(assignment);

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("security.events.v1"), captor.capture());

        DomainEventEnvelope<?> envelope = captor.getValue();
        assertThat(envelope.eventType()).isEqualTo(RoleAssignmentChangedV1.EVENT_TYPE);
        assertThat(envelope.aggregateId()).isEqualTo(ASSIGNMENT_ID);
        assertThat(envelope.sourceService()).isEqualTo("pos-security-service");
        assertThat(envelope.actor()).isEqualTo("admin-user");

        // The payload carries current state, not a delta — safe to reprocess.
        RoleAssignmentChangedV1 payload = (RoleAssignmentChangedV1) envelope.payload();
        assertThat(payload.assignmentId()).isEqualTo(ASSIGNMENT_ID);
        assertThat(payload.userId()).isEqualTo(USER_ID);
        // The one requirement this event exists to satisfy: a consumer keyed by something other
        // than userId (pos-people, by personId) must be able to resolve this row without a
        // callback into pos-security-service.
        assertThat(payload.username()).isEqualTo("jane.doe");
        assertThat(payload.roleId()).isEqualTo(ROLE_ID);
        assertThat(payload.roleName()).isEqualTo("SHOP_MANAGER");
        // The role's location scope, denormalized from Role — not a property of the assignment
        // (V38/#1875 dropped that from RoleAssignment; see ADR-0061 §1).
        assertThat(payload.roleLocationScope()).isEqualTo("ALL");
        assertThat(payload.revokedAt()).isNull();
        assertThat(payload.effectiveEndDate()).isNull();
    }

    @Test
    @DisplayName("publishes the role's LOCATION scope on grant, not just ALL")
    void publishesLocationScopedRoleOnGrant() {
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        var emitter = new RoleAssignmentEventEmitter(TEST_CLOCK, providerFor(writer), "security.events.v1");
        RoleAssignment assignment = assignment(LocationScope.LOCATION);

        emitter.roleAssignmentChanged(assignment);

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("security.events.v1"), captor.capture());
        RoleAssignmentChangedV1 payload =
                (RoleAssignmentChangedV1) captor.getValue().payload();
        assertThat(payload.roleLocationScope()).isEqualTo("LOCATION");
    }

    @Test
    @DisplayName("publishes a fact carrying revokedAt and the closed window on revoke")
    void publishesFactOnRevoke() {
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        var emitter = new RoleAssignmentEventEmitter(TEST_CLOCK, providerFor(writer), "security.events.v1");
        RoleAssignment assignment = assignment();
        LocalDateTime endDate = LocalDateTime.parse("2026-09-22T12:00:00");
        Instant revokedAt = Instant.parse("2026-09-22T12:00:00Z");
        assignment.revoke(endDate, revokedAt);

        emitter.roleAssignmentChanged(assignment);

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("security.events.v1"), captor.capture());

        RoleAssignmentChangedV1 payload =
                (RoleAssignmentChangedV1) captor.getValue().payload();
        assertThat(payload.username()).isEqualTo("jane.doe");
        assertThat(payload.effectiveEndDate()).isEqualTo(endDate);
        assertThat(payload.revokedAt()).isEqualTo(revokedAt);
        assertThat(payload.roleLocationScope()).isEqualTo("ALL");
    }

    @Test
    @DisplayName("publishes the role's LOCATION scope on revoke, not just ALL")
    void publishesLocationScopedRoleOnRevoke() {
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        var emitter = new RoleAssignmentEventEmitter(TEST_CLOCK, providerFor(writer), "security.events.v1");
        RoleAssignment assignment = assignment(LocationScope.LOCATION);
        assignment.revoke(LocalDateTime.parse("2026-09-22T12:00:00"), Instant.parse("2026-09-22T12:00:00Z"));

        emitter.roleAssignmentChanged(assignment);

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("security.events.v1"), captor.capture());
        RoleAssignmentChangedV1 payload =
                (RoleAssignmentChangedV1) captor.getValue().payload();
        assertThat(payload.roleLocationScope()).isEqualTo("LOCATION");
    }

    @Test
    @DisplayName("a publish failure (serialization/envelope) is swallowed, not propagated to the caller")
    void publishFailureIsSwallowed() {
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        doThrow(new IllegalStateException("boom")).when(writer).publish(any(), any());
        var emitter = new RoleAssignmentEventEmitter(TEST_CLOCK, providerFor(writer), "security.events.v1");
        RoleAssignment assignment = assignment();

        assertThatCode(() -> emitter.roleAssignmentChanged(assignment)).doesNotThrowAnyException();

        verify(writer).publish(any(), any());
    }

    @Test
    @DisplayName("is a no-op when Kafka is disabled, so the assignment write never depends on the broker")
    void noOpWhenKafkaDisabled() {
        ObjectProvider<OutboxEventWriter> absent = providerFor(null);
        var emitter = new RoleAssignmentEventEmitter(TEST_CLOCK, absent, "security.events.v1");

        emitter.roleAssignmentChanged(assignment());

        verify(absent).getIfAvailable();
    }

    @Test
    @DisplayName("an unauthenticated write records the system actor rather than failing")
    void unauthenticatedWriteRecordsSystemActor() {
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        var emitter = new RoleAssignmentEventEmitter(TEST_CLOCK, providerFor(writer), "security.events.v1");

        emitter.roleAssignmentChanged(assignment());

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(any(), captor.capture());
        assertThat(captor.getValue().actor()).isEqualTo("system");
        verify(writer, never()).publishRaw(any(), any(), any());
    }

    @Test
    @DisplayName("aggregateVersion advances with the assignment's last write")
    void aggregateVersionTracksLastWrite() {
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        var emitter = new RoleAssignmentEventEmitter(TEST_CLOCK, providerFor(writer), "security.events.v1");
        RoleAssignment edited = assignment();
        Instant editedAt = Instant.parse("2026-09-22T11:59:00Z");
        edited.setLastModifiedAt(editedAt);

        emitter.roleAssignmentChanged(edited);

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(any(), captor.capture());
        assertThat(captor.getValue().aggregateVersion()).isEqualTo(editedAt.toEpochMilli());
    }

    @Test
    @DisplayName("a never-modified assignment falls back to its creation stamp")
    void aggregateVersionFallsBackToCreation() {
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        var emitter = new RoleAssignmentEventEmitter(TEST_CLOCK, providerFor(writer), "security.events.v1");

        emitter.roleAssignmentChanged(assignment());

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(any(), captor.capture());
        assertThat(captor.getValue().aggregateVersion())
                .isEqualTo(Instant.parse("2026-09-01T00:00:00Z").toEpochMilli());
    }

    @Test
    @DisplayName("payload carries the assignment's tenantId alongside the envelope's own")
    void payloadCarriesTenantId() {
        // tenantId is stamped by Hibernate (@TenantId) with no application setter, so a
        // never-persisted entity carries none; this pins that the payload round-trips whatever
        // the entity holds rather than silently dropping the field.
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        var emitter = new RoleAssignmentEventEmitter(TEST_CLOCK, providerFor(writer), "security.events.v1");
        RoleAssignment assignment = assignment();

        emitter.roleAssignmentChanged(assignment);

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(any(), captor.capture());
        RoleAssignmentChangedV1 payload =
                (RoleAssignmentChangedV1) captor.getValue().payload();
        assertThat(payload.tenantId()).isEqualTo(assignment.getTenantId());
    }
}
