package com.positivity.domainevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class DomainEventEnvelopeTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-08T12:00:00Z"), ZoneOffset.UTC);

    record PartyUpdatedV1(UUID partyId, String displayName) {}

    private DomainEventEnvelope<PartyUpdatedV1> envelope() {
        UUID partyId = UUID.randomUUID();
        return DomainEventEnvelope.of(
                "customer.party.updated",
                1,
                partyId,
                42L,
                "pos-customer",
                "corr-123",
                "user-9",
                new PartyUpdatedV1(partyId, "Acme"),
                FIXED_CLOCK);
    }

    @Test
    void factoryPopulatesUuidV7EventIdAndClockTime() {
        DomainEventEnvelope<PartyUpdatedV1> event = envelope();

        assertThat(event.eventId().version()).isEqualTo(7);
        assertThat(event.occurredAtUtc()).isEqualTo(Instant.parse("2026-07-08T12:00:00Z"));
        assertThat(event.recordKey()).isEqualTo(event.aggregateId().toString());
    }

    @Test
    void serializationRoundTripPreservesAllFields() {
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        DomainEventEnvelope<PartyUpdatedV1> event = envelope();

        String json = mapper.writeValueAsString(event);
        DomainEventEnvelope<PartyUpdatedV1> read = mapper.readValue(
                json, mapper.getTypeFactory().constructParametricType(DomainEventEnvelope.class, PartyUpdatedV1.class));

        assertThat(read).isEqualTo(event);
        assertThat(json)
                .contains("\"eventType\":\"customer.party.updated\"")
                .contains("\"schemaVersion\":1")
                .contains("\"aggregateVersion\":42")
                .contains("\"sourceService\":\"pos-customer\"");
    }

    @Test
    void rejectsInvalidEventTypeSchemaVersionAndSourceService() {
        UUID id = UUID.randomUUID();

        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        DomainEventEnvelope.of("BadType", 1, id, 0L, "pos-customer", null, null, "p", FIXED_CLOCK))
                .withMessageContaining("eventType");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainEventEnvelope.of(
                        "customer.party.updated", 0, id, 0L, "pos-customer", null, null, "p", FIXED_CLOCK))
                .withMessageContaining("schemaVersion");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainEventEnvelope.of(
                        "customer.party.updated", 1, id, -1L, "pos-customer", null, null, "p", FIXED_CLOCK))
                .withMessageContaining("aggregateVersion");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainEventEnvelope.of(
                        "customer.party.updated", 1, id, 0L, "customer", null, null, "p", FIXED_CLOCK))
                .withMessageContaining("sourceService");
    }

    @Test
    void tenantIsStampedByThePublishPathAndSerialized() {
        UUID tenant = UUID.fromString("01900000-0000-7000-8000-000000000001");
        DomainEventEnvelope<PartyUpdatedV1> unstamped = envelope();
        assertThat(unstamped.tenantId())
                .as("of(...) leaves the tenant to the outbox writer")
                .isNull();

        DomainEventEnvelope<PartyUpdatedV1> stamped = unstamped.stampedWith(tenant);

        assertThat(stamped.tenantId()).isEqualTo(tenant);
        assertThat(stamped)
                .usingRecursiveComparison()
                .ignoringFields("tenantId")
                .isEqualTo(unstamped);
        assertThat(stamped.requireTenantId()).isEqualTo(tenant);
        assertThat(stamped.stampedWith(tenant))
                .as("already stamped with the bound tenant: unchanged")
                .isSameAs(stamped);

        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        String json = mapper.writeValueAsString(stamped);
        assertThat(json).contains("\"tenantId\":\"" + tenant + "\"");
        DomainEventEnvelope<PartyUpdatedV1> read = mapper.readValue(
                json, mapper.getTypeFactory().constructParametricType(DomainEventEnvelope.class, PartyUpdatedV1.class));
        assertThat(read).isEqualTo(stamped);
    }

    @Test
    void explicitTenantOverloadAndWithTenantId() {
        UUID tenant = UUID.fromString("01900000-0000-7000-8000-000000000000");
        UUID partyId = UUID.randomUUID();

        DomainEventEnvelope<String> explicit = DomainEventEnvelope.of(
                "tenant.reconciliation.manifest", 1, partyId, 0L, "pos-tenant", tenant, null, null, "p", FIXED_CLOCK);
        assertThat(explicit.tenantId()).isEqualTo(tenant);

        DomainEventEnvelope<PartyUpdatedV1> rebound =
                envelope().withTenantId(tenant).withTenantId(tenant);
        assertThat(rebound.tenantId()).isEqualTo(tenant);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> envelope().withTenantId(null))
                .withMessageContaining("tenantId");
    }

    @Test
    void publishingUnderAnotherTenantIsRefused() {
        UUID tenantA = UUID.fromString("01900000-0000-7000-8000-000000000001");
        UUID tenantB = UUID.fromString("01900000-0000-7000-8000-000000000002");
        DomainEventEnvelope<PartyUpdatedV1> forA = envelope().withTenantId(tenantA);

        assertThatIllegalStateException()
                .isThrownBy(() -> forA.stampedWith(tenantB))
                .withMessageContaining(tenantA.toString())
                .withMessageContaining(tenantB.toString());
        assertThatIllegalStateException()
                .isThrownBy(() -> envelope().requireTenantId())
                .withMessageContaining("no tenantId");
    }

    @Test
    void messagesPublishedBeforeTheFieldExistedStillDeserialize() {
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        String legacy =
                "{\"eventId\":\"01980001-0000-7000-8000-000000000001\",\"eventType\":\"customer.party.updated\","
                        + "\"schemaVersion\":1,\"aggregateId\":\"01980001-0000-7000-8000-000000000002\",\"aggregateVersion\":3,"
                        + "\"occurredAtUtc\":\"2026-07-08T12:00:00Z\",\"sourceService\":\"pos-customer\","
                        + "\"correlationId\":null,\"actor\":null,\"payload\":\"p\"}";

        DomainEventEnvelope<String> read = mapper.readValue(
                legacy, mapper.getTypeFactory().constructParametricType(DomainEventEnvelope.class, String.class));

        assertThat(read.tenantId()).isNull();
        assertThat(read.eventType()).isEqualTo("customer.party.updated");
    }

    @Test
    void optionalCorrelationAndActorMayBeNull() {
        DomainEventEnvelope<String> event = DomainEventEnvelope.of(
                "location.profile.updated", 1, UUID.randomUUID(), 7L, "pos-location", null, null, "p", FIXED_CLOCK);

        assertThat(event.correlationId()).isNull();
        assertThat(event.actor()).isNull();
    }
}
