package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.workorder.internal.dto.CreateEstimateFromAppointmentRequest;
import com.positivity.workorder.internal.dto.CreateEstimateFromAppointmentResponse;
import com.positivity.workorder.internal.repository.EstimateRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Pins that the appointment bridge fills the audited creator.
 *
 * <p>It did not. {@code Estimate.createdById} is {@code @Column(nullable = false)} and every other
 * create path sets it from the security context, but createEstimateFromAppointment built its entity
 * without it - so the insert died on the constraint and the endpoint answered a bare 500 to every
 * caller. Nothing covered the persisting path: the existing controller test mocks the service.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("An estimate created from an appointment records who created it")
class EstimateFromAppointmentCreatedByIT {

    @Autowired
    private EstimateService estimateService;

    @Autowired
    private EstimateRepository estimateRepository;

    @Test
    @DisplayName("the bridge persists, and the idempotent replay returns the same estimate")
    void bridgePersistsAndReplays() {
        UUID appointmentId = UUID.randomUUID();
        CreateEstimateFromAppointmentRequest request = new CreateEstimateFromAppointmentRequest();
        request.setIdempotencyKey(UUID.randomUUID());
        request.setAppointmentId(appointmentId);
        request.setCustomerId(UUID.randomUUID());
        request.setVehicleId(UUID.randomUUID());
        request.setLocationId(UUID.randomUUID());

        CreateEstimateFromAppointmentResponse first = estimateService.createEstimateFromAppointment(request);

        assertThat(first.isCreated()).isTrue();
        assertThat(first.getEstimateId()).isNotNull();
        assertThat(estimateRepository.findById(first.getEstimateId()))
                .get()
                .extracting(estimate -> estimate.getCreatedById())
                .as("created_by_id is NOT NULL; the bridge has to supply it like every other create path")
                .isNotNull();

        // Idempotent on the appointment, not on the key: the service looks the
        // estimate up by appointmentId. Replaying with the same key would pass
        // either way, so the replay deliberately carries a different one.
        CreateEstimateFromAppointmentRequest replayRequest = new CreateEstimateFromAppointmentRequest();
        replayRequest.setIdempotencyKey(UUID.randomUUID());
        replayRequest.setAppointmentId(appointmentId);
        replayRequest.setCustomerId(UUID.randomUUID());
        replayRequest.setVehicleId(UUID.randomUUID());
        replayRequest.setLocationId(UUID.randomUUID());
        assertThat(replayRequest.getIdempotencyKey())
                .as("the replay must not reuse the first key, or it proves nothing about the appointment")
                .isNotEqualTo(request.getIdempotencyKey());

        CreateEstimateFromAppointmentResponse replay = estimateService.createEstimateFromAppointment(replayRequest);
        assertThat(replay.isCreated()).isFalse();
        assertThat(replay.getEstimateId()).isEqualTo(first.getEstimateId());
    }
}
