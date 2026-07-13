package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.client.ManagerApprovalClient;
import com.positivity.invoice.internal.dto.ElevateResponse;
import com.positivity.invoice.internal.entity.ExtEmployeeReplica;
import com.positivity.invoice.internal.exception.ElevationDeniedException;
import com.positivity.invoice.internal.repository.ExtEmployeeReplicaRepository;
import com.positivity.security.common.LogSanitizer;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates manager-approval elevation: verifies the named manager (by employee
 * number) is an active employee holding {@code invoice:finalize:override}, then mints a
 * short-lived elevation token scoped to the target invoice.
 */
@Service
public class ElevationService {

    private static final Logger log = LoggerFactory.getLogger(ElevationService.class);

    private final ManagerApprovalClient managerApprovalClient;

    private final ExtEmployeeReplicaRepository extEmployeeReplicaRepository;

    private final ElevationTokenService tokenService;

    public ElevationService(
            @NonNull ManagerApprovalClient managerApprovalClient,
            @NonNull ExtEmployeeReplicaRepository extEmployeeReplicaRepository,
            @NonNull ElevationTokenService tokenService) {
        this.managerApprovalClient = managerApprovalClient;
        this.extEmployeeReplicaRepository = extEmployeeReplicaRepository;
        this.tokenService = tokenService;
    }

    /**
     * Verify the approving manager and mint an elevation token for {@code invoiceId}.
     *
     * @throws ElevationDeniedException when the employee number is unknown/inactive or
     *                                  the person lacks the finalize-override authority
     */
    @NonNull
    public ElevateResponse elevate(@NonNull String managerEmployeeNumber, @NonNull UUID invoiceId) {
        // Employee-number -> active person resolution is local since #877: the
        // ext_people_employee replica mirrors people.employee.updated facts.
        UUID managerPersonId = extEmployeeReplicaRepository
                .findFirstByEmployeeNumberIgnoreCase(managerEmployeeNumber)
                .filter(employee -> "ACTIVE".equals(employee.getStatus()))
                .map(ExtEmployeeReplica::getPersonId)
                .orElseThrow(() -> new ElevationDeniedException("Manager approval denied"));

        if (!managerApprovalClient.personHoldsFinalizeOverride(managerPersonId)) {
            throw new ElevationDeniedException("Manager approval denied");
        }

        ElevationTokenService.MintedToken minted = tokenService.mint(invoiceId, managerPersonId);
        // ADR-0018: audit the approving manager and the invoice the grant authorises.
        log.info(
                "Elevation token minted: approverPersonId={}, invoiceId={}",
                LogSanitizer.forLog(managerPersonId),
                LogSanitizer.forLog(invoiceId));
        return new ElevateResponse(minted.token(), minted.expiresAt());
    }
}
