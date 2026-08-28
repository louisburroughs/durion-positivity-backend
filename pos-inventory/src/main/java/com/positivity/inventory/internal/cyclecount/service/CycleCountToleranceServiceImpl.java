package com.positivity.inventory.internal.cyclecount.service;

import com.positivity.inventory.internal.dto.cyclecount.tolerance.CreateCycleCountToleranceRequest;
import com.positivity.inventory.internal.dto.cyclecount.tolerance.CycleCountToleranceResponse;
import com.positivity.inventory.internal.dto.cyclecount.tolerance.UpdateCycleCountToleranceRequest;
import com.positivity.inventory.internal.entity.CycleCountTolerance;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.CycleCountToleranceRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of cycle-count tolerance admin CRUD (ADR-0055 stage 4, #1416).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CycleCountToleranceServiceImpl implements CycleCountToleranceService {

    private static final String RESOURCE_TYPE = "CycleCountTolerance";

    private final CycleCountToleranceRepository repository;

    @Override
    public CycleCountToleranceResponse create(CreateCycleCountToleranceRequest request) {
        UUID productId = request.getProductId();
        String storageLocation = normalize(request.getStorageLocation());

        findActiveScope(productId, storageLocation).ifPresent(existing -> {
            throw new IllegalArgumentException(
                    "An active tolerance already exists for this scope: " + existing.getToleranceId());
        });

        CycleCountTolerance saved = repository.save(CycleCountTolerance.builder()
                .productId(productId)
                .storageLocation(storageLocation)
                .absoluteTolerance(request.getAbsoluteTolerance())
                .percentageTolerance(request.getPercentageTolerance())
                .active(true)
                .createdBy(request.getCreatedBy())
                .build());
        return toResponse(saved);
    }

    @Override
    public CycleCountToleranceResponse update(@NonNull UUID toleranceId, UpdateCycleCountToleranceRequest request) {
        CycleCountTolerance tolerance = getEntity(toleranceId);

        boolean reactivating = Boolean.TRUE.equals(request.getActive()) && !tolerance.isActive();
        if (reactivating) {
            findActiveScope(tolerance.getProductId(), tolerance.getStorageLocation())
                    .filter(existing -> !existing.getToleranceId().equals(toleranceId))
                    .ifPresent(existing -> {
                        throw new IllegalArgumentException(
                                "Cannot reactivate: another active tolerance already occupies this scope: "
                                        + existing.getToleranceId());
                    });
        }

        tolerance.setAbsoluteTolerance(request.getAbsoluteTolerance());
        tolerance.setPercentageTolerance(request.getPercentageTolerance());
        tolerance.setActive(Boolean.TRUE.equals(request.getActive()));
        return toResponse(repository.save(tolerance));
    }

    @Override
    @Transactional(readOnly = true)
    public CycleCountToleranceResponse get(@NonNull UUID toleranceId) {
        return toResponse(getEntity(toleranceId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CycleCountToleranceResponse> list() {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public void delete(@NonNull UUID toleranceId) {
        if (!repository.existsById(toleranceId)) {
            throw new ResourceNotFoundException(RESOURCE_TYPE, toleranceId.toString());
        }
        repository.deleteById(toleranceId);
    }

    private CycleCountTolerance getEntity(UUID toleranceId) {
        return repository
                .findById(toleranceId)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_TYPE, toleranceId.toString()));
    }

    /**
     * Looks up an active row at the exact (productId, storageLocation) scope, trying the finder
     * that matches which sides are null — mirrors {@link CycleCountToleranceResolver}'s
     * most-specific-first scopes, but here as an exact-match existence check rather than a
     * fallback chain.
     */
    private Optional<CycleCountTolerance> findActiveScope(@Nullable UUID productId, @Nullable String storageLocation) {
        if (productId != null && storageLocation != null) {
            return repository.findByProductIdAndStorageLocationAndActiveTrue(productId, storageLocation);
        }
        if (productId != null) {
            return repository.findByProductIdAndStorageLocationIsNullAndActiveTrue(productId);
        }
        if (storageLocation != null) {
            return repository.findByProductIdIsNullAndStorageLocationAndActiveTrue(storageLocation);
        }
        return repository.findByProductIdIsNullAndStorageLocationIsNullAndActiveTrue();
    }

    private @Nullable String normalize(@Nullable String storageLocation) {
        return (storageLocation == null || storageLocation.isBlank()) ? null : storageLocation.trim();
    }

    private CycleCountToleranceResponse toResponse(CycleCountTolerance tolerance) {
        return CycleCountToleranceResponse.builder()
                .toleranceId(tolerance.getToleranceId())
                .productId(tolerance.getProductId())
                .storageLocation(tolerance.getStorageLocation())
                .absoluteTolerance(tolerance.getAbsoluteTolerance())
                .percentageTolerance(tolerance.getPercentageTolerance())
                .active(tolerance.isActive())
                .createdBy(tolerance.getCreatedBy())
                .createdAt(tolerance.getCreatedAt())
                .updatedAt(tolerance.getUpdatedAt())
                .build();
    }
}
