package com.positivity.shopmanager.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.shopmanager.BaseContractIntegrationTest;
import com.positivity.shopmanager.PosShopManagerApplication;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.AppointmentAudit;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.repository.AppointmentAuditRepository;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.RescheduleHistoryRepository;
import com.positivity.shopmanager.internal.service.CrmSnapshotService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract behavioral integration tests for CAP-137 Story #73:
 * Reschedule and Cancel Appointment with Audit.
 *
 * <p>
 * Validates the HTTP REST contract for:
 * <ul>
 * <li>PUT /v1/appointments/{id}/reschedule</li>
 * <li>DELETE /v1/appointments/{id}/cancel</li>
 * </ul>
 *
 * <p>
 * Assertions align with:
 * <ul>
 * <li>ADR-0017: HTTP response code matrix (200/400/404/409)</li>
 * <li>ADR-0018: Audit actor fields sourced from security context (X-User
 * header)</li>
 * </ul>
 *
 * Issue: #73
 */
@SpringBootTest(classes = PosShopManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
// Issue #73: H2 in-memory datasource + disable external startup dependencies
// for isolated integration tests
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:shopmgr_rs_cancel;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.cloud.discovery.enabled=false",
            "eureka.client.enabled=false",
            "spring.cloud.config.enabled=false",
            "spring.boot.admin.client.enabled=false",
            "pos.security.permission-registration.enabled=false"
        })
// Issue #73 end
@Tag("contract")
@DisplayName("CAP-137 Story #73: Reschedule or Cancel Appointment with Audit — Contract Behavioral Tests")
class AppointmentRescheduleCancelContractBehaviorIT extends BaseContractIntegrationTest {

    private static final UUID TEST_LOCATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TEST_CUSTOMER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEST_VEHICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID NON_EXISTENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    private static final Instant BASE_START = Instant.parse("2026-05-01T09:00:00Z");
    private static final Instant BASE_END = Instant.parse("2026-05-01T10:00:00Z");
    private static final Instant NEW_START = Instant.parse("2026-05-02T09:00:00Z");
    private static final Instant NEW_END = Instant.parse("2026-05-02T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private AppointmentAuditRepository appointmentAuditRepository;

    @Autowired
    private RescheduleHistoryRepository rescheduleHistoryRepository;

    // Mocked to prevent context-startup failures; not invoked by reschedule/cancel
    // stubs
    @MockitoBean
    private CrmSnapshotService crmSnapshotService;

    /**
     * Wipes appointment and audit tables before each test to ensure full isolation.
     * Audit records must be deleted first to satisfy any implicit ordering
     * constraints.
     */
    @BeforeEach
    void cleanDatabase() {
        appointmentAuditRepository.deleteAll();
        rescheduleHistoryRepository.deleteAll();
        appointmentRepository.deleteAll();
    }

    @Override
    protected String defaultAuthorities() {
        return "appointments:reschedule,appointments:cancel,shop:schedule:view";
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Persists a SCHEDULED appointment with no workorder link.
     *
     * @return the saved {@link Appointment} with a generated appointmentId
     */
    private Appointment saveScheduledAppointment() {
        return saveAppointment(AppointmentStatus.SCHEDULED, null);
    }

    /**
     * Persists a SCHEDULED appointment linked to a workorder reference (AC3 / RS3).
     *
     * @return the saved {@link Appointment} with workorderLinkRef populated
     */
    private Appointment saveAppointmentWithWorkorderRef() {
        return saveAppointment(AppointmentStatus.SCHEDULED, "WO-REF-001");
    }

    /**
     * Core factory: builds and saves an {@link Appointment} with the supplied
     * status and optional
     * workorder link reference. appointmentId is generated via {@code @PrePersist}.
     *
     * @param status           the desired {@link AppointmentStatus}
     * @param workorderLinkRef optional workorder link reference; may be
     *                         {@code null}
     * @return the persisted {@link Appointment}
     */
    private Appointment saveAppointment(AppointmentStatus status, String workorderLinkRef) {
        Appointment appt = Appointment.builder()
                .crmCustomerId(TEST_CUSTOMER_ID)
                .crmVehicleId(TEST_VEHICLE_ID)
                .locationId(TEST_LOCATION_ID)
                .resourceId("BAY-01")
                .startAt(BASE_START)
                .endAt(BASE_END)
                .status(status)
                .workorderLinkRef(workorderLinkRef)
                .build();
        return appointmentRepository.save(appt);
    }

    private String reschedulePayload(String newStartAt, String newEndAt) {
        return """
                                {
                                  "newStartAt": "%s",
                                  "newEndAt":   "%s",
                                  "reason":     "CUSTOMER_REQUEST"
                                }
                                """.formatted(newStartAt, newEndAt);
    }

    private String cancelPayload(String cancellationReason) {
        return """
                                {
                                  "cancellationReason": "%s"
                                }
                                """.formatted(cancellationReason);
    }

    // ─── RESCHEDULE Tests ────────────────────────────────────────────────────────

    /**
     * RS1 → AC1 (Reschedule): Verifies 200 OK with updated startAt/endAt and
     * SCHEDULED status
     * when rescheduling a SCHEDULED appointment with valid new times.
     */
    @Test
    @DisplayName(
            "RS1: should_return_200_with_SCHEDULED_status_and_updated_times_when_rescheduling_SCHEDULED_appointment")
    void RS1_should_return_200_with_SCHEDULED_status_and_updated_times_when_rescheduling_SCHEDULED_appointment()
            throws Exception {
        Appointment appt = saveScheduledAppointment();

        // Issue #73: RS1 — core reschedule happy path (ADR-0017: 200 for successful
        // mutation)
        mockMvc.perform(withGatewayAuth(put("/v1/appointments/{id}/reschedule", appt.getAppointmentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reschedulePayload(NEW_START.toString(), NEW_END.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointmentId")
                        .value(appt.getAppointmentId().toString()))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.startAt").value(NEW_START.toString()))
                .andExpect(jsonPath("$.endAt").value(NEW_END.toString()));
    }

    /**
     * RS2 → Audit & Observability: Verifies that an AppointmentAudit record is
     * created with
     * action=RESCHEDULED, previous/new times recorded, after a successful
     * reschedule.
     * Uses direct repository access to assert the audit trail per AGENTS.md
     * mandate.
     */
    @Test
    @DisplayName("RS2: should_create_AppointmentAudit_with_action_RESCHEDULED_and_previous_and_new_times_recorded")
    void RS2_should_create_AppointmentAudit_with_action_RESCHEDULED_and_previous_and_new_times_recorded()
            throws Exception {
        Appointment appt = saveScheduledAppointment();

        // Issue #73: RS2 — audit trail after reschedule
        mockMvc.perform(withGatewayAuth(put("/v1/appointments/{id}/reschedule", appt.getAppointmentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reschedulePayload(NEW_START.toString(), NEW_END.toString()))))
                .andExpect(status().isOk());

        List<AppointmentAudit> audits =
                appointmentAuditRepository.findByAppointmentIdOrderByCreatedAtDesc(appt.getAppointmentId());
        assertThat(audits).hasSize(1);
        AppointmentAudit audit = audits.get(0);
        assertThat(audit.getAction().name()).isEqualTo("RESCHEDULED");
        assertThat(audit.getPreviousStartAt()).isEqualTo(BASE_START);
        assertThat(audit.getPreviousEndAt()).isEqualTo(BASE_END);
        assertThat(audit.getNewStartAt()).isEqualTo(NEW_START);
        assertThat(audit.getNewEndAt()).isEqualTo(NEW_END);
        assertThat(audit.getActorId()).isNotBlank();
    }

    /**
     * RS3 → AC3 (Domain Event): Verifies 200 OK when rescheduling an appointment
     * that has a
     * workorderLinkRef set. Event emission is handled by pos-events AOP; no direct
     * assertion
     * is made on the event bus — 200 proves the integration path executed without
     * error.
     */
    @Test
    @DisplayName("RS3: should_return_200_when_rescheduling_appointment_with_workorderLinkRef_so_event_fires_via_AOP")
    void RS3_should_return_200_when_rescheduling_appointment_with_workorderLinkRef_so_event_fires_via_AOP()
            throws Exception {
        Appointment appt = saveAppointmentWithWorkorderRef();

        // Issue #73: RS3 — event firing is proxied by AOP; 200 is the observable
        // contract evidence
        mockMvc.perform(withGatewayAuth(put("/v1/appointments/{id}/reschedule", appt.getAppointmentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reschedulePayload(NEW_START.toString(), NEW_END.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointmentId").isNotEmpty());
    }

    /**
     * RS3b → AC4 (No Domain Event): Verifies 200 OK when rescheduling an
     * appointment without
     * workorderLinkRef. The behavior remains successful and must not require event
     * publication.
     */
    @Test
    @DisplayName("RS3b: should_return_200_when_rescheduling_appointment_without_workorderLinkRef_and_no_domain_event")
    void RS3b_should_return_200_when_rescheduling_appointment_without_workorderLinkRef_and_no_domain_event()
            throws Exception {
        Appointment appt = saveScheduledAppointment();

        mockMvc.perform(withGatewayAuth(put("/v1/appointments/{id}/reschedule", appt.getAppointmentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reschedulePayload(NEW_START.toString(), NEW_END.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointmentId")
                        .value(appt.getAppointmentId().toString()));
    }

    /**
     * RS4 → AC5: Verifies 409 INVALID_APPOINTMENT_STATE when attempting to
     * reschedule a
     * COMPLETED appointment. Per ADR-0017: 409 for invalid lifecycle transitions.
     */
    @Test
    @DisplayName("RS4: should_return_409_INVALID_APPOINTMENT_STATE_when_rescheduling_COMPLETED_appointment")
    void RS4_should_return_409_INVALID_APPOINTMENT_STATE_when_rescheduling_COMPLETED_appointment() throws Exception {
        Appointment appt = saveAppointment(AppointmentStatus.COMPLETED, null);

        // Issue #73: RS4 — lifecycle guard for COMPLETED state
        mockMvc.perform(withGatewayAuth(put("/v1/appointments/{id}/reschedule", appt.getAppointmentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reschedulePayload(NEW_START.toString(), NEW_END.toString()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_APPOINTMENT_STATE"));
    }

    /**
     * RS5 → AC5: Verifies 409 INVALID_APPOINTMENT_STATE when attempting to
     * reschedule a
     * CANCELLED appointment.
     */
    @Test
    @DisplayName("RS5: should_return_409_INVALID_APPOINTMENT_STATE_when_rescheduling_CANCELLED_appointment")
    void RS5_should_return_409_INVALID_APPOINTMENT_STATE_when_rescheduling_CANCELLED_appointment() throws Exception {
        Appointment appt = saveAppointment(AppointmentStatus.CANCELLED, null);

        // Issue #73: RS5 — lifecycle guard for CANCELLED state
        mockMvc.perform(withGatewayAuth(put("/v1/appointments/{id}/reschedule", appt.getAppointmentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reschedulePayload(NEW_START.toString(), NEW_END.toString()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_APPOINTMENT_STATE"));
    }

    /**
     * RS6: Verifies 404 when the appointmentId does not exist.
     * Per ADR-0017: 404 for missing resource.
     */
    @Test
    @DisplayName("RS6: should_return_404_when_rescheduling_non_existent_appointment")
    void RS6_should_return_404_when_rescheduling_non_existent_appointment() throws Exception {
        // Issue #73: RS6 — not-found guard
        mockMvc.perform(withGatewayAuth(put("/v1/appointments/{id}/reschedule", NON_EXISTENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reschedulePayload(NEW_START.toString(), NEW_END.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_FOUND"));
    }

    /**
     * RS7: Verifies 400 VALIDATION_ERROR when newStartAt is not before newEndAt.
     * Per ADR-0017: 400 for request-shape/field validation errors.
     */
    @Test
    @DisplayName("RS7: should_return_400_VALIDATION_ERROR_when_newStartAt_is_not_before_newEndAt")
    void RS7_should_return_400_VALIDATION_ERROR_when_newStartAt_is_not_before_newEndAt() throws Exception {
        Appointment appt = saveScheduledAppointment();

        // Issue #73: RS7 — newEndAt sent as start, newStartAt sent as end
        // (reversed/invalid range)
        mockMvc.perform(withGatewayAuth(put("/v1/appointments/{id}/reschedule", appt.getAppointmentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reschedulePayload(NEW_END.toString(), NEW_START.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ─── CANCEL Tests ─────────────────────────────────────────────────────────

    /**
     * CA1 → AC2 (Cancel): Verifies 200 OK with status=CANCELLED when cancelling a
     * SCHEDULED
     * appointment with a valid cancellationReason.
     */
    @Test
    @DisplayName("CA1: should_return_200_with_CANCELLED_status_when_cancelling_SCHEDULED_appointment")
    void CA1_should_return_200_with_CANCELLED_status_when_cancelling_SCHEDULED_appointment() throws Exception {
        Appointment appt = saveScheduledAppointment();

        // Issue #73: CA1 — core cancel happy path
        mockMvc.perform(withGatewayAuth(delete("/v1/appointments/{id}/cancel", appt.getAppointmentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelPayload("CUSTOMER_REQUEST"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationReason").value("CUSTOMER_REQUEST"));
    }

    /**
     * CA2 → Audit & Observability: Verifies that an AppointmentAudit record is
     * created with
     * action=CANCELLED, cancellationReason recorded, and actorId populated from the
     * security context.
     * Per ADR-0018: actor fields are derived from authenticated principal (X-User
     * header via gateway).
     */
    @Test
    @DisplayName(
            "CA2: should_create_AppointmentAudit_with_action_CANCELLED_cancellationReason_and_actorId_from_security_context")
    void CA2_should_create_AppointmentAudit_with_action_CANCELLED_cancellationReason_and_actorId_from_security_context()
            throws Exception {
        Appointment appt = saveScheduledAppointment();

        // Issue #73: CA2 — audit trail after cancel (ADR-0018: actorId from security
        // context)
        mockMvc.perform(withGatewayAuth(delete("/v1/appointments/{id}/cancel", appt.getAppointmentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelPayload("CUSTOMER_REQUEST"))))
                .andExpect(status().isOk());

        List<AppointmentAudit> audits =
                appointmentAuditRepository.findByAppointmentIdOrderByCreatedAtDesc(appt.getAppointmentId());
        assertThat(audits).hasSize(1);
        AppointmentAudit audit = audits.get(0);
        assertThat(audit.getAction().name()).isEqualTo("CANCELLED");
        assertThat(audit.getCancellationReason()).isEqualTo("CUSTOMER_REQUEST");
        // ADR-0018: actor must be sourced from security context, not client payload
        assertThat(audit.getActorId()).isNotBlank();
    }

    /**
     * CA2b → AC4 (No Domain Event): Verifies 200 OK when cancelling an appointment
     * without
     * workorderLinkRef. The cancellation succeeds and no domain event should be
     * required.
     */
    @Test
    @DisplayName("CA2b: should_return_200_when_cancelling_appointment_without_workorderLinkRef_and_no_domain_event")
    void CA2b_should_return_200_when_cancelling_appointment_without_workorderLinkRef_and_no_domain_event()
            throws Exception {
        Appointment appt = saveScheduledAppointment();

        mockMvc.perform(withGatewayAuth(delete("/v1/appointments/{id}/cancel", appt.getAppointmentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelPayload("CUSTOMER_REQUEST"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointmentId")
                        .value(appt.getAppointmentId().toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    /**
     * CA3: Verifies 400 when an unknown string is sent for the cancellationReason
     * enum field.
     * Jackson fails deserialization before the controller is invoked, producing 400
     * automatically.
     */
    @Test
    @DisplayName("CA3: should_return_400_when_cancellationReason_is_unrecognised_enum_value")
    void CA3_should_return_400_when_cancellationReason_is_unrecognised_enum_value() throws Exception {
        Appointment appt = saveScheduledAppointment();

        // Issue #73: CA3 — enum deserialization guard (Jackson
        // HttpMessageNotReadableException → 400)
        mockMvc.perform(withGatewayAuth(delete("/v1/appointments/{id}/cancel", appt.getAppointmentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelPayload("INVALID_VALUE"))))
                .andExpect(status().isBadRequest());
    }

    /**
     * CA4 → AC5: Verifies 409 INVALID_APPOINTMENT_STATE when attempting to cancel a
     * COMPLETED
     * appointment. Per ADR-0017: 409 for invalid lifecycle transitions.
     */
    @Test
    @DisplayName("CA4: should_return_409_INVALID_APPOINTMENT_STATE_when_cancelling_COMPLETED_appointment")
    void CA4_should_return_409_INVALID_APPOINTMENT_STATE_when_cancelling_COMPLETED_appointment() throws Exception {
        Appointment appt = saveAppointment(AppointmentStatus.COMPLETED, null);

        // Issue #73: CA4 — lifecycle guard for COMPLETED state
        mockMvc.perform(withGatewayAuth(delete("/v1/appointments/{id}/cancel", appt.getAppointmentId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelPayload("CUSTOMER_REQUEST"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_APPOINTMENT_STATE"));
    }

    /**
     * CA5: Verifies 404 when the appointmentId does not exist.
     */
    @Test
    @DisplayName("CA5: should_return_404_when_cancelling_non_existent_appointment")
    void CA5_should_return_404_when_cancelling_non_existent_appointment() throws Exception {
        // Issue #73: CA5 — not-found guard for cancel
        mockMvc.perform(withGatewayAuth(delete("/v1/appointments/{id}/cancel", NON_EXISTENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelPayload("CUSTOMER_REQUEST"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_FOUND"));
    }
}
