package com.positivity.warranty.internal.service;

import com.positivity.warranty.internal.dto.RegistrationRequest;
import com.positivity.warranty.internal.dto.RegistrationResponse;
import com.positivity.warranty.internal.entity.WarrantyRegistration;
import com.positivity.warranty.internal.enums.RegistrationStatus;
import com.positivity.warranty.internal.exception.WarrantyNotFoundException;
import com.positivity.warranty.internal.repository.WarrantyPolicyRepository;
import com.positivity.warranty.internal.repository.WarrantyRegistrationRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RegistrationServiceImpl implements RegistrationService {

    static final String NOT_FOUND_CODE = "WARRANTY_REGISTRATION_NOT_FOUND";

    private final WarrantyRegistrationRepository registrationRepository;
    private final WarrantyPolicyRepository policyRepository;

    @Override
    @NonNull
    @Transactional
    public RegistrationResponse create(@NonNull RegistrationRequest request) {
        requirePolicyExists(request.policyId());
        WarrantyRegistration registration = new WarrantyRegistration();
        apply(registration, request);
        if (request.status() == null) {
            registration.setStatus(RegistrationStatus.ACTIVE);
        }
        return toResponse(registrationRepository.save(registration));
    }

    @Override
    @NonNull
    @Transactional
    public RegistrationResponse update(@NonNull UUID id, @NonNull RegistrationRequest request) {
        WarrantyRegistration registration = load(id);
        requirePolicyExists(request.policyId());
        apply(registration, request);
        return toResponse(registrationRepository.save(registration));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public RegistrationResponse getById(@NonNull UUID id) {
        return toResponse(load(id));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<RegistrationResponse> search(
            @Nullable UUID customerId, @Nullable UUID vehicleId, @Nullable RegistrationStatus status) {
        List<WarrantyRegistration> registrations;
        if (customerId != null && vehicleId != null) {
            registrations = registrationRepository.findByCustomerIdAndVehicleId(customerId, vehicleId);
        } else if (customerId != null && status != null) {
            registrations = registrationRepository.findByCustomerIdAndStatus(customerId, status);
        } else if (customerId != null) {
            registrations = registrationRepository.findByCustomerId(customerId);
        } else if (vehicleId != null) {
            registrations = registrationRepository.findByVehicleId(vehicleId);
        } else {
            registrations = registrationRepository.findAll();
        }
        return registrations.stream()
                .filter(r -> status == null || r.getStatus() == status)
                .map(RegistrationServiceImpl::toResponse)
                .toList();
    }

    private void requirePolicyExists(@NonNull UUID policyId) {
        if (!policyRepository.existsById(policyId)) {
            throw new WarrantyNotFoundException(
                    PolicyServiceImpl.NOT_FOUND_CODE, "Warranty policy '" + policyId + "' was not found");
        }
    }

    @NonNull
    private WarrantyRegistration load(@NonNull UUID id) {
        return registrationRepository
                .findById(id)
                .orElseThrow(() -> new WarrantyNotFoundException(
                        NOT_FOUND_CODE, "Warranty registration '" + id + "' was not found"));
    }

    private static void apply(WarrantyRegistration registration, RegistrationRequest request) {
        registration.setPolicyId(request.policyId());
        registration.setCustomerId(request.customerId());
        registration.setVehicleId(request.vehicleId());
        registration.setSourceInvoiceId(request.sourceInvoiceId());
        registration.setSourceInvoiceLineId(request.sourceInvoiceLineId());
        registration.setContractNumber(request.contractNumber());
        registration.setPurchaseDate(request.purchaseDate());
        registration.setExpiresAt(request.expiresAt());
        if (request.status() != null) {
            registration.setStatus(request.status());
        }
    }

    @NonNull
    private static RegistrationResponse toResponse(@NonNull WarrantyRegistration registration) {
        return new RegistrationResponse(
                registration.getId(),
                registration.getPolicyId(),
                registration.getCustomerId(),
                registration.getVehicleId(),
                registration.getSourceInvoiceId(),
                registration.getSourceInvoiceLineId(),
                registration.getContractNumber(),
                registration.getPurchaseDate(),
                registration.getExpiresAt(),
                registration.getStatus(),
                registration.getCreatedAt(),
                registration.getUpdatedAt(),
                registration.getVersion());
    }
}
