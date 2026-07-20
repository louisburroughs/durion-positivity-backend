package com.positivity.tax.internal.service;

import com.positivity.tax.common.enums.ExemptionReasonCode;
import com.positivity.tax.internal.dto.ExemptionCertificateRequest;
import com.positivity.tax.internal.dto.ExemptionCertificateResponse;
import com.positivity.tax.internal.entity.ExemptionCertificate;
import com.positivity.tax.internal.enums.ExemptionCertificateStatus;
import com.positivity.tax.internal.repository.ExemptionCertificateRepository;
import com.positivity.tax.service.ExemptionCertificateService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Registry service for tax exemption certificates (story T3). Also implements the
 * read-only {@link ActiveCertificateLookup} port used by the calculate path so a single
 * component owns certificate persistence and validity resolution.
 */
@Slf4j
@Service
@Transactional
public class ExemptionCertificateServiceImpl implements ExemptionCertificateService, ActiveCertificateLookup {

    private final ExemptionCertificateRepository repository;

    public ExemptionCertificateServiceImpl(ExemptionCertificateRepository repository) {
        this.repository = repository;
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<ExemptionCertificateResponse> list(@Nullable String customerId) {
        List<ExemptionCertificate> certificates = (customerId == null || customerId.isBlank())
                ? repository.findAll()
                : repository.findByCustomerIdOrderByEffectiveFromDesc(customerId);
        return certificates.stream().map(ExemptionCertificateResponse::from).toList();
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public ExemptionCertificateResponse get(@NonNull UUID id) {
        return repository.findById(id).map(ExemptionCertificateResponse::from).orElseThrow(() -> notFound(id));
    }

    @Override
    @NonNull
    public ExemptionCertificateResponse create(@NonNull ExemptionCertificateRequest request) {
        ExemptionCertificate entity = ExemptionCertificate.builder()
                .customerId(request.getCustomerId())
                .stateScope(request.getStateScope())
                .reasonCode(request.getReasonCode())
                .certificateNumber(request.getCertificateNumber())
                .effectiveFrom(request.getEffectiveFrom())
                .expiresAt(request.getExpiresAt())
                .status(request.getStatus() != null ? request.getStatus() : ExemptionCertificateStatus.ACTIVE)
                .build();
        ExemptionCertificate saved = repository.save(entity);
        log.info("Created exemption certificate {} for customer {}", saved.getId(), saved.getCustomerId());
        return ExemptionCertificateResponse.from(saved);
    }

    @Override
    @NonNull
    public ExemptionCertificateResponse update(@NonNull UUID id, @NonNull ExemptionCertificateRequest request) {
        ExemptionCertificate entity = repository.findById(id).orElseThrow(() -> notFound(id));
        entity.setCustomerId(request.getCustomerId());
        entity.setStateScope(request.getStateScope());
        entity.setReasonCode(request.getReasonCode());
        entity.setCertificateNumber(request.getCertificateNumber());
        entity.setEffectiveFrom(request.getEffectiveFrom());
        entity.setExpiresAt(request.getExpiresAt());
        if (request.getStatus() != null) {
            entity.setStatus(request.getStatus());
        }
        ExemptionCertificate saved = repository.save(entity);
        log.info("Updated exemption certificate {}", saved.getId());
        return ExemptionCertificateResponse.from(saved);
    }

    // ------------------------------------------------------------------
    // ActiveCertificateLookup (calculate path)
    // ------------------------------------------------------------------

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public Optional<ActiveCertificate> findActive(
            @Nullable String customerId,
            @Nullable UUID certificateId,
            @Nullable String stateScope,
            @Nullable ExemptionReasonCode requestedReason,
            @NonNull LocalDate date) {

        if (certificateId != null) {
            return repository
                    .findById(certificateId)
                    .filter(c -> isValid(c, customerId, date))
                    .map(c -> new ActiveCertificate(c.getId(), c.getReasonCode()));
        }
        if (customerId == null || customerId.isBlank()) {
            return Optional.empty();
        }
        return repository.findActiveForCustomer(customerId, stateScope, requestedReason, date, Limit.of(1)).stream()
                .findFirst()
                .map(c -> new ActiveCertificate(c.getId(), c.getReasonCode()));
    }

    private static boolean isValid(ExemptionCertificate c, @Nullable String customerId, LocalDate date) {
        boolean active = c.getStatus() == ExemptionCertificateStatus.ACTIVE;
        boolean effective = !c.getEffectiveFrom().isAfter(date);
        boolean notExpired = c.getExpiresAt() == null || !c.getExpiresAt().isBefore(date);
        boolean customerMatches = customerId == null || customerId.isBlank() || customerId.equals(c.getCustomerId());
        return active && effective && notExpired && customerMatches;
    }

    private static ResponseStatusException notFound(UUID id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Exemption certificate not found: " + id);
    }
}
