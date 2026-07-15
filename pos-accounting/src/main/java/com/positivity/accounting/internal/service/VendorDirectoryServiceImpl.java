package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.VendorResponse;
import com.positivity.accounting.internal.entity.Vendor;
import com.positivity.accounting.internal.repository.VendorRepository;
import com.positivity.accounting.service.VendorDirectoryService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of the AP vendor directory (Issue #816).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VendorDirectoryServiceImpl implements VendorDirectoryService {

    /** Server-side cap on typeahead result size. */
    public static final int MAX_RESULTS = 100;

    private static final int DEFAULT_LIMIT = 20;

    private final VendorRepository vendorRepository;

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<VendorResponse> searchVendors(@Nullable String name, int limit) {
        int capped = limit > 0 ? Math.min(limit, MAX_RESULTS) : DEFAULT_LIMIT;
        Pageable page = PageRequest.of(0, capped);

        List<Vendor> vendors = (name == null || name.isBlank())
                ? vendorRepository.findAllByOrderByNameAsc(page)
                : vendorRepository.findByNameContainingIgnoreCaseOrderByNameAsc(name.trim(), page);

        return vendors.stream().map(VendorDirectoryServiceImpl::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Optional<VendorResponse> getVendorById(@NonNull UUID vendorId) {
        return vendorRepository.findById(vendorId).map(VendorDirectoryServiceImpl::toResponse);
    }

    @Override
    @Transactional
    public void recordVendor(@NonNull UUID vendorId, @Nullable String vendorName) {
        if (vendorName == null || vendorName.isBlank()) {
            return;
        }
        String name = vendorName.trim();

        Optional<Vendor> existing = vendorRepository.findById(vendorId);
        if (existing.isPresent()) {
            Vendor vendor = existing.get();
            if (!name.equals(vendor.getName())) {
                log.info("Refreshing vendor directory name | vendorId={}", vendorId);
                vendor.setName(name);
                vendorRepository.save(vendor);
            }
            return;
        }

        log.info("Adding vendor to directory | vendorId={}", vendorId);
        vendorRepository.save(new Vendor(vendorId, name));
    }

    private static VendorResponse toResponse(Vendor vendor) {
        return VendorResponse.builder()
                .vendorId(vendor.getVendorId())
                .name(vendor.getName())
                .vendorNumber(vendor.getVendorNumber())
                .status(vendor.getStatus() != null ? vendor.getStatus().name() : null)
                .build();
    }
}
