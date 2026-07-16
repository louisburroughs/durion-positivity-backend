package com.positivity.warranty.internal.service;

import com.positivity.warranty.internal.dto.ProviderRequest;
import com.positivity.warranty.internal.dto.ProviderResponse;
import com.positivity.warranty.internal.entity.WarrantyProvider;
import com.positivity.warranty.internal.enums.ProviderType;
import com.positivity.warranty.internal.exception.WarrantyNotFoundException;
import com.positivity.warranty.internal.repository.WarrantyProviderRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProviderServiceImpl implements ProviderService {

    static final String NOT_FOUND_CODE = "WARRANTY_PROVIDER_NOT_FOUND";

    private final WarrantyProviderRepository providerRepository;

    @Override
    @NonNull
    @Transactional
    public ProviderResponse create(@NonNull ProviderRequest request) {
        WarrantyProvider provider = new WarrantyProvider();
        apply(provider, request);
        if (request.status() == null) {
            provider.setStatus(WarrantyProvider.STATUS_ACTIVE);
        }
        return toResponse(providerRepository.save(provider));
    }

    @Override
    @NonNull
    @Transactional
    public ProviderResponse update(@NonNull UUID id, @NonNull ProviderRequest request) {
        WarrantyProvider provider = load(id);
        apply(provider, request);
        return toResponse(providerRepository.save(provider));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public ProviderResponse getById(@NonNull UUID id) {
        return toResponse(load(id));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<ProviderResponse> list(@Nullable String status, @Nullable ProviderType providerType) {
        List<WarrantyProvider> providers;
        if (status != null) {
            providers = providerRepository.findByStatus(status);
        } else if (providerType != null) {
            providers = providerRepository.findByProviderType(providerType);
        } else {
            providers = providerRepository.findAll();
        }
        return providers.stream()
                .filter(p -> providerType == null || p.getProviderType() == providerType)
                .map(ProviderServiceImpl::toResponse)
                .toList();
    }

    @NonNull
    private WarrantyProvider load(@NonNull UUID id) {
        return providerRepository
                .findById(id)
                .orElseThrow(() ->
                        new WarrantyNotFoundException(NOT_FOUND_CODE, "Warranty provider '" + id + "' was not found"));
    }

    private static void apply(WarrantyProvider provider, ProviderRequest request) {
        provider.setName(request.name());
        provider.setProviderType(request.providerType());
        if (request.status() != null) {
            provider.setStatus(request.status());
        }
        provider.setApVendorId(request.apVendorId());
        provider.setManufacturerId(request.manufacturerId());
        provider.setClaimSubmissionMethod(request.claimSubmissionMethod());
        provider.setPortalUrl(request.portalUrl());
        provider.setContactName(request.contactName());
        provider.setContactPhone(request.contactPhone());
        provider.setContactEmail(request.contactEmail());
    }

    @NonNull
    private static ProviderResponse toResponse(@NonNull WarrantyProvider provider) {
        return new ProviderResponse(
                provider.getId(),
                provider.getName(),
                provider.getStatus(),
                provider.getProviderType(),
                provider.getApVendorId(),
                provider.getManufacturerId(),
                provider.getClaimSubmissionMethod(),
                provider.getPortalUrl(),
                provider.getContactName(),
                provider.getContactPhone(),
                provider.getContactEmail(),
                provider.getCreatedAt(),
                provider.getUpdatedAt(),
                provider.getVersion());
    }
}
