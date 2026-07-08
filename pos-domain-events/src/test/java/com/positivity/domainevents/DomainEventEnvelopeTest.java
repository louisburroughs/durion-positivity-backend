package com.positivity.domainevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
    void optionalCorrelationAndActorMayBeNull() {
        DomainEventEnvelope<String> event = DomainEventEnvelope.of(
                "location.profile.updated", 1, UUID.randomUUID(), 7L, "pos-location", null, null, "p", FIXED_CLOCK);

        assertThat(event.correlationId()).isNull();
        assertThat(event.actor()).isNull();
    }
}
