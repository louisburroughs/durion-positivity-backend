package com.positivity.catalog.service;

import com.positivity.catalog.internal.dto.ProductLifecycleResponse;
import com.positivity.catalog.internal.dto.ProductLifecycleUpdateRequest;
import com.positivity.catalog.internal.dto.ProductReplacementRequest;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.ProductLifecycleState;
import com.positivity.catalog.internal.entity.ProductReplacementEntity;
import com.positivity.catalog.internal.repository.ProductReplacementRepository;
import com.positivity.catalog.internal.repository.ProductRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class ProductLifecycleService {

    private static final String DISCONTINUED_REACTIVATION_ERROR = "Discontinued products cannot be reactivated. Specify a replacement product instead.";

    private final ProductRepository productRepository;
    private final ProductReplacementRepository productReplacementRepository;
    private final Counter lifecycleUpdateSuccessCounter;
    private final Counter lifecycleUpdateDeniedCounter;

    public ProductLifecycleService(
            ProductRepository productRepository,
            ProductReplacementRepository productReplacementRepository,
            MeterRegistry meterRegistry) {
        this.productRepository = productRepository;
        this.productReplacementRepository = productReplacementRepository;
        this.lifecycleUpdateSuccessCounter = meterRegistry.counter("product.lifecycle.state_change.success.count");
        this.lifecycleUpdateDeniedCounter = meterRegistry.counter("product.lifecycle.state_change.denied.count");
    }

    @Transactional(readOnly = true)
    public ProductLifecycleResponse getLifecycle(UUID productId) {
        ProductEntity product = findProduct(productId);
        List<ProductReplacementEntity> replacements = productReplacementRepository
                .findByOriginalProductIdAndDeletedAtIsNullOrderByPriorityOrderAsc(productId);
        return toResponse(product, replacements);
    }

    @Transactional
    public ProductLifecycleResponse updateLifecycle(UUID productId, ProductLifecycleUpdateRequest request) {
        if (request == null || request.getLifecycleState() == null) {
            lifecycleUpdateDeniedCounter.increment();
            throw new IllegalArgumentException("lifecycleState is required");
        }
        ProductEntity product = findProduct(productId);
        ProductLifecycleState currentState = resolveCurrentState(product);
        ProductLifecycleState nextState = request.getLifecycleState();

        if (currentState == ProductLifecycleState.DISCONTINUED
                && nextState != ProductLifecycleState.DISCONTINUED) {
            lifecycleUpdateDeniedCounter.increment();
            throw new IllegalStateException(DISCONTINUED_REACTIVATION_ERROR);
        }

        boolean overridePermissionUsed = nextState == ProductLifecycleState.DISCONTINUED
                || currentState == ProductLifecycleState.DISCONTINUED;

        if (overridePermissionUsed && !hasAuthority("product:lifecycle:override_discontinued")) {
            lifecycleUpdateDeniedCounter.increment();
            throw new SecurityException("Missing required permission: product:lifecycle:override_discontinued");
        }

        if (overridePermissionUsed && isBlank(request.getOverrideReason())) {
            lifecycleUpdateDeniedCounter.increment();
            throw new IllegalArgumentException("overrideReason is required when discontinued override permission is used");
        }

        Instant effectiveAt = resolveEffectiveAt(request.getEffectiveAt(), request.getEffectiveDate());
        if (effectiveAt.isBefore(Instant.now())) {
            lifecycleUpdateDeniedCounter.increment();
            throw new IllegalArgumentException("effectiveAt cannot be in the past");
        }

        product.setLifecycleState(nextState);
        product.setLifecycleStateEffectiveAt(effectiveAt);
        product.setLastStateChangedBy(request.getChangedBy());
        product.setLastStateChangedAt(Instant.now());
        product.setLifecycleOverrideReason(request.getOverrideReason());
        ProductEntity saved = productRepository.save(product);

        lifecycleUpdateSuccessCounter.increment();
        log.info(
                "Product lifecycle updated: productId={}, oldState={}, newState={}, effectiveAt={}, changedBy={}, overridePermissionUsed={}",
                productId,
                currentState,
                nextState,
                effectiveAt,
                request.getChangedBy(),
                overridePermissionUsed);

        List<ProductReplacementEntity> replacements = productReplacementRepository
                .findByOriginalProductIdAndDeletedAtIsNullOrderByPriorityOrderAsc(productId);
        return toResponse(saved, replacements);
    }

    @Transactional
    public ProductLifecycleResponse.ReplacementOption addReplacement(UUID productId, ProductReplacementRequest request) {
        if (request == null || request.getReplacementProductId() == null) {
            throw new IllegalArgumentException("replacementProductId is required");
        }
        if (request.getPriorityOrder() == null || request.getPriorityOrder() <= 0) {
            throw new IllegalArgumentException("priorityOrder must be greater than zero");
        }

        ProductEntity originalProduct = findProduct(productId);
        if (resolveCurrentState(originalProduct) != ProductLifecycleState.DISCONTINUED) {
            throw new IllegalStateException("Replacement products can only be added when lifecycleState is DISCONTINUED");
        }

        findProduct(request.getReplacementProductId());

        ProductReplacementEntity replacement = new ProductReplacementEntity();
        replacement.setOriginalProductId(productId);
        replacement.setReplacementProductId(request.getReplacementProductId());
        replacement.setPriorityOrder(request.getPriorityOrder());
        replacement.setNotes(request.getNotes());
        replacement.setEffectiveAt(request.getEffectiveAt() != null ? request.getEffectiveAt() : Instant.now());

        ProductReplacementEntity saved = productReplacementRepository.save(replacement);

        return ProductLifecycleResponse.ReplacementOption.builder()
                .replacementId(saved.getReplacementId())
                .replacementProductId(saved.getReplacementProductId())
                .priorityOrder(saved.getPriorityOrder())
                .notes(saved.getNotes())
                .effectiveAt(saved.getEffectiveAt())
                .build();
    }

    private ProductEntity findProduct(UUID productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
    }

    private ProductLifecycleState resolveCurrentState(ProductEntity product) {
        return product.getLifecycleState() == null ? ProductLifecycleState.ACTIVE : product.getLifecycleState();
    }

    private Instant resolveEffectiveAt(Instant effectiveAt, java.time.LocalDate effectiveDate) {
        if (effectiveAt != null) {
            return effectiveAt;
        }
        if (effectiveDate != null) {
            return effectiveDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return Instant.now();
    }

    private boolean hasAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(grantedAuthority -> authority.equals(grantedAuthority.getAuthority()));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private ProductLifecycleResponse toResponse(ProductEntity product, List<ProductReplacementEntity> replacements) {
        List<ProductLifecycleResponse.ReplacementOption> options = replacements.stream()
                .map(replacement -> ProductLifecycleResponse.ReplacementOption.builder()
                        .replacementId(replacement.getReplacementId())
                        .replacementProductId(replacement.getReplacementProductId())
                        .priorityOrder(replacement.getPriorityOrder())
                        .notes(replacement.getNotes())
                        .effectiveAt(replacement.getEffectiveAt())
                        .build())
                .toList();

        return ProductLifecycleResponse.builder()
                .productId(product.getId())
                .lifecycleState(resolveCurrentState(product))
                .lifecycleStateEffectiveAt(product.getLifecycleStateEffectiveAt())
                .lastStateChangedBy(product.getLastStateChangedBy())
                .lastStateChangedAt(product.getLastStateChangedAt())
                .lifecycleOverrideReason(product.getLifecycleOverrideReason())
                .replacementOptions(options)
                .build();
    }
}
