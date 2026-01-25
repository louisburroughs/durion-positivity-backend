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

    public AppointmentResponse create(AppointmentCreateRequest request, String idempotencyKey) {
        log.info("[AppointmentsService] create idempotencyKey={}, request={}", idempotencyKey, request);
        // TODO: Implement appointment creation orchestration (ShopMgmt domain)
        return null; // Placeholder, controller will return 501
    }

    public AppointmentResponse getById(String appointmentId) {
        log.info("[AppointmentsService] getById appointmentId={}", appointmentId);
        // TODO: Implement appointment retrieval orchestration
        return null; // Placeholder, controller will return 501
    }
}
