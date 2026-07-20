package com.positivity.tax.service;

import com.positivity.tax.internal.dto.ExemptionCertificateRequest;
import com.positivity.tax.internal.dto.ExemptionCertificateResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Registry API for tax exemption certificates (story T3, decision D-T1).
 * <p>
 * The certificate registry lives in pos-tax because an exemption is tax semantics, not
 * CRM identity. This is a minimally-stateful surface: certificates drive whether a
 * claimed exemption on the calculate path is honored or denied (D-T2).
 */
public interface ExemptionCertificateService {

    /**
     * List certificates, optionally filtered by customer.
     *
     * @param customerId optional customer filter; when {@code null} all certificates are returned
     * @return matching certificates
     */
    @NonNull
    List<ExemptionCertificateResponse> list(@Nullable String customerId);

    /**
     * Get a certificate by id.
     *
     * @param id the certificate id
     * @return the certificate
     * @throws org.springframework.web.server.ResponseStatusException 404 when not found
     */
    @NonNull
    ExemptionCertificateResponse get(@NonNull UUID id);

    /**
     * Create a certificate.
     *
     * @param request the create payload
     * @return the created certificate
     */
    @NonNull
    ExemptionCertificateResponse create(@NonNull ExemptionCertificateRequest request);

    /**
     * Update an existing certificate.
     *
     * @param id      the certificate id
     * @param request the update payload
     * @return the updated certificate
     * @throws org.springframework.web.server.ResponseStatusException 404 when not found
     */
    @NonNull
    ExemptionCertificateResponse update(@NonNull UUID id, @NonNull ExemptionCertificateRequest request);
}
