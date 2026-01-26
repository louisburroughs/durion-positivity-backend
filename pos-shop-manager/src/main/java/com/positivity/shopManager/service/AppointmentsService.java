package com.positivity.shopManager.service;

import com.positivity.shopManager.dto.AppointmentCreateRequest;
import com.positivity.shopManager.dto.AppointmentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentsService {

    public AppointmentResponse create(AppointmentCreateRequest request, String idempotencyKey, String correlationId) {
        log.info("[AppointmentsService] create idempotencyKey={}, correlationId={}, request={}", idempotencyKey,
                correlationId, request);
        // TODO: Implement appointment creation orchestration (ShopMgmt domain)
        // correlationId should be propagated to downstream services and included in
        // error responses
        return null; // Placeholder, controller will return 501
    }

    public AppointmentResponse getById(String appointmentId, String correlationId) {
        log.info("[AppointmentsService] getById appointmentId={}, correlationId={}", appointmentId, correlationId);
        // TODO: Implement appointment retrieval orchestration
        // correlationId should be propagated to downstream services and included in
        // error responses
        return null; // Placeholder, controller will return 501
    }
}
