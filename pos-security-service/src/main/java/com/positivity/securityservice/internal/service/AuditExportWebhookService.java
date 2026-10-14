package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.AuditExportWebhookRegistrationResponse;
import com.positivity.securityservice.internal.dto.AuditExportWebhookRequest;
import com.positivity.securityservice.internal.dto.AuditExportWebhookResponse;
import org.jspecify.annotations.NonNull;

/**
 * The bound tenant's one audit export webhook destination (#2497): where its {@code WEBHOOK} exports
 * are delivered, and the secret that signs them.
 */
public interface AuditExportWebhookService {

    /**
     * Registers the bound tenant's destination, replacing any earlier one, and issues a new signing
     * secret that the returned response alone carries.
     *
     * @throws com.positivity.securityservice.internal.exception.AuditExportWebhookInvalidException
     *     when the URL is not an allowed destination
     */
    @NonNull
    AuditExportWebhookRegistrationResponse register(@NonNull AuditExportWebhookRequest request);

    /**
     * The bound tenant's destination, without its secret.
     *
     * @throws com.positivity.securityservice.internal.exception.AuditExportWebhookNotRegisteredException
     *     when none is registered
     */
    @NonNull
    AuditExportWebhookResponse get();

    /** Removes the bound tenant's destination; nothing to remove is not an error. */
    void delete();
}
