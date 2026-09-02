package com.positivity.shopmanager.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.events.EmitEvent;
import com.positivity.shopmanager.internal.config.EventTypes;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/**
 * Contract tests for the audit events emitted by the roster read endpoints.
 *
 * <p>
 * Roster queries are significant reads: they fan out across the mechanic read
 * model and its skill enrichment, so they carry an {@code @EmitEvent} id like the
 * module's other read endpoints. An id that is missing — or present on the
 * controller but absent from {@link EventTypes} — drops the endpoint out of the
 * startup registration PUT, so {@code pos-event-receiver} never learns its
 * latency thresholds and the endpoint silently disappears from audit reporting.
 *
 * <p>
 * These tests pin the annotation to the method and the id to the registry.
 */
@DisplayName("Roster read endpoints — audit event contract")
class RosterAuditEventContractTest {

    private static final String MECHANIC_ROSTER_LIST = "SHOPMGR_MECHANIC_ROSTER_LIST";
    private static final String LOCATION_TECHNICIAN_LIST = "SHOPMGR_LOCATION_TECHNICIAN_LIST";

    private static List<String> registeredTypeCodes() {
        return EventTypes.all().stream()
                .map(com.positivity.events.EventTypeRegistration::getTypeCode)
                .toList();
    }

    private static EmitEvent emitEventOn(Method method) {
        return method.getAnnotation(EmitEvent.class);
    }

    @Test
    @DisplayName("listMechanics emits SHOPMGR_MECHANIC_ROSTER_LIST at api version 1")
    void listMechanics_emitsRegisteredAuditEvent() throws NoSuchMethodException {
        Method listMechanics = MechanicRosterController.class.getMethod(
                "listMechanics",
                com.positivity.shopmanager.internal.enums.MechanicStatus.class,
                String.class,
                Pageable.class);

        EmitEvent emitEvent = emitEventOn(listMechanics);

        assertThat(emitEvent).as("listMechanics must carry @EmitEvent").isNotNull();
        assertThat(emitEvent.id()).isEqualTo(MECHANIC_ROSTER_LIST);
        assertThat(emitEvent.apiVersion()).isEqualTo("1");
    }

    @Test
    @DisplayName("listLocationTechnicians emits SHOPMGR_LOCATION_TECHNICIAN_LIST at api version 1")
    void listLocationTechnicians_emitsRegisteredAuditEvent() throws NoSuchMethodException {
        Method listLocationTechnicians = TechnicianController.class.getMethod(
                "listLocationTechnicians",
                UUID.class,
                com.positivity.shopmanager.internal.enums.MechanicStatus.class,
                String.class,
                Pageable.class);

        EmitEvent emitEvent = emitEventOn(listLocationTechnicians);

        assertThat(emitEvent)
                .as("listLocationTechnicians must carry @EmitEvent")
                .isNotNull();
        assertThat(emitEvent.id()).isEqualTo(LOCATION_TECHNICIAN_LIST);
        assertThat(emitEvent.apiVersion()).isEqualTo("1");
    }

    @Test
    @DisplayName("both roster event ids are registered in EventTypes so startup registration covers them")
    void rosterEventIds_areRegistered() {
        assertThat(registeredTypeCodes()).contains(MECHANIC_ROSTER_LIST, LOCATION_TECHNICIAN_LIST);
    }
}
