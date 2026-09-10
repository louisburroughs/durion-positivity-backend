package com.positivity.tenancy.replica;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TenantProjectionEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-000000000123");

    private static Optional<TenantProjectionEvent> parse(String json) {
        return TenantProjectionEvent.parse(MAPPER.readTree(json));
    }

    @Test
    void parsesAProjectionFact() {
        Optional<TenantProjectionEvent> event = parse("""
                {"eventId":"e1","eventType":"tenant.suspended","aggregateVersion":7,
                 "payload":{"tenantId":"%s","slug":"acme","displayName":"Acme","status":"SUSPENDED"}}
                """.formatted(TENANT));

        assertThat(event).isPresent();
        assertThat(event.get().tenantId()).isEqualTo(TENANT);
        assertThat(event.get().slug()).isEqualTo("acme");
        assertThat(event.get().aggregateVersion()).isEqualTo(7L);
        assertThat(event.get().isActive()).isFalse();
    }

    @Test
    void createdCarriesTheProjectionTooAndExtraFieldsAreIgnored() {
        Optional<TenantProjectionEvent> event = parse("""
                {"eventId":"e2","eventType":"tenant.created","aggregateVersion":1,
                 "payload":{"tenantId":"%s","slug":"acme","displayName":null,"status":"ACTIVE",
                            "initialAdminEmail":"owner@acme.example"}}
                """.formatted(TENANT));

        assertThat(event).isPresent();
        assertThat(event.get().displayName()).isNull();
        assertThat(event.get().isActive()).isTrue();
    }

    @Test
    void provisionedMalformedAndForeignTypesAreEmpty() {
        assertThat(parse("{\"eventId\":\"e3\",\"eventType\":\"tenant.provisioned\",\"payload\":{\"tenantId\":\"%s\"}}"
                        .formatted(TENANT)))
                .isEmpty();
        assertThat(
                        parse(
                                "{\"eventId\":\"e4\",\"eventType\":\"tenant.updated\",\"payload\":{\"tenantId\":\"nope\",\"slug\":\"a\",\"status\":\"ACTIVE\"}}"))
                .isEmpty();
        assertThat(parse(
                        "{\"eventId\":\"e5\",\"eventType\":\"tenant.updated\",\"payload\":{\"tenantId\":\"%s\",\"status\":\"ACTIVE\"}}"
                                .formatted(TENANT)))
                .as("slug is required")
                .isEmpty();
        assertThat(parse("{\"eventType\":\"tenant.updated\",\"payload\":{}}")).isEmpty();
        assertThat(parse("{\"eventId\":\"e6\",\"eventType\":\"location.bay.updated\",\"payload\":{}}"))
                .isEmpty();
    }
}
