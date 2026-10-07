package com.positivity.securityservice.internal.controller;

import com.positivity.securityservice.internal.dto.StepUpRequest;
import com.positivity.securityservice.internal.dto.StepUpResponse;
import com.positivity.securityservice.internal.service.StepUpService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The step-up credential check (CAP:550 S16, #2512; AW31): a service-to-service surface, called by
 * pos-order when a manager approves a drawer cash movement at a shared register with their own
 * credentials.
 *
 * <p>Internal: it lives under {@code /internal/...}, which the gateway refuses at the edge whatever
 * the caller holds ({@code SecurityGatewayConfig} {@code INTERNAL_PATH}), so it is reachable only
 * inside the mesh through the load balancer, and it is absent from the OpenAPI document. The caller
 * authenticates with the mesh service credential on {@code X-Internal-Api-Secret} (its own chain,
 * {@code SecurityConfig#internalServiceFilterChain}, no CSRF, no gateway identity headers) and binds
 * its own request's tenant with {@code X-Tenant-Id}; the credentials are checked in that tenant only
 * (ADR-0062).
 *
 * <p>200 answers who the person is and whether they hold the permission asked about; any failed check
 * answers one 403 body ({@code STEP_UP_DENIED}), never 401. No token is issued and no session opened.
 */
@Hidden
@RestController
@RequestMapping(StepUpController.PATH)
@PreAuthorize("hasRole('INTERNAL_SERVICE')")
@RequiredArgsConstructor
public class StepUpController {

    public static final String PATH = "/internal/v1/auth/step-up";

    private final StepUpService stepUpService;

    @PostMapping
    public ResponseEntity<StepUpResponse> stepUp(@Valid @RequestBody StepUpRequest request) {
        return ResponseEntity.ok(stepUpService.verify(
                request.username(), request.password(), request.permission(), request.locationId()));
    }
}
