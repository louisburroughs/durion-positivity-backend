package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.ProductLifecycleResponse;
import com.positivity.catalog.internal.dto.ProductLifecycleUpdateRequest;
import com.positivity.catalog.internal.dto.ProductReplacementRequest;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.entity.ProductLifecycleState;
import com.positivity.catalog.internal.entity.ProductReplacementEntity;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogForbiddenOperationException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.ProductReplacementRepository;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.service.ProductLifecycleService;
import com.positivity.security.common.SecurityContextHelper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class ProductLifecycleServiceImpl implements ProductLifecycleService {

    private static final String DISCONTINUED_REACTIVATION_ERROR = "Discontinued products cannot be reactivated. Specify a replacement product instead.";
    private static final Duration EFFECTIVE_AT_PAST_TOLERANCE = Duration.ofSeconds(2);

    private final ProductRepository productRepository;
    private final ProductReplacementRepository productReplacementRepository;
    private final Counter lifecycleUpdateSuccessCounter;
    private final Counter lifecycleUpdateDeniedCounter;
    private final Clock clock;
    private final ProductDetailCacheInvalidationPublisher productDetailCacheInvalidationPublisher;

    public ProductLifecycleServiceImpl(
            ProductRepository productRepository,
            ProductReplacementRepository productReplacementRepository,
            MeterRegistry meterRegistry,
            Clock clock,
            ProductDetailCacheInvalidationPublisher productDetailCacheInvalidationPublisher) {
        this.productRepository = productRepository;
        this.productReplacementRepository = productReplacementRepository;
        this.lifecycleUpdateSuccessCounter = meterRegistry.counter("product.lifecycle.state_change.success.count");
        this.lifecycleUpdateDeniedCounter = meterRegistry.counter("product.lifecycle.state_change.denied.count");
        this.clock = clock;
        this.productDetailCacheInvalidationPublisher = productDetailCacheInvalidationPublisher;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductLifecycleResponse getLifecycle(UUID productId) {
        ProductEntity product = findProduct(productId);
        List<ProductReplacementEntity> replacements = productReplacementRepository
            .findByOriginalProduct_IdAndDeletedAtIsNullOrderByPriorityOrderAsc(productId);
        return toResponse(product, replacements);
    }

    @Override
    @Transactional
    public ProductLifecycleResponse updateLifecycle(UUID productId, ProductLifecycleUpdateRequest request) {
        return doUpdateLifecycle(productId, request);
    }

    @Override
    @Transactional
    public ProductLifecycleResponse.ReplacementOption addReplacement(UUID productId,
            ProductReplacementRequest request) {
        return doAddReplacement(productId, request);
    }

    @Override
    @Transactional
    public ProductLifecycleResponse setLifecycleState(
            @NonNull UUID productId,
            @NonNull ProductLifecycleState newState,
            Instant effectiveAt,
            String overrideReason,
            LocalDate effectiveDate) {
        ProductLifecycleUpdateRequest request = new ProductLifecycleUpdateRequest();
        request.setLifecycleState(newState);
        request.setEffectiveAt(effectiveAt);
        request.setEffectiveDate(effectiveDate != null ? effectiveDate : LocalDate.now(ZoneOffset.UTC));
        request.setOverrideReason(overrideReason);
        request.setChangedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
        return doUpdateLifecycle(productId, request);
    }

    @Override
    @Transactional
    public ProductLifecycleResponse.ReplacementOption addReplacementProduct(
            @NonNull UUID discontinuedProductId,
            @NonNull UUID replacementProductId,
            int priorityOrder,
            String notes,
            Instant effectiveAt) {
        ProductReplacementRequest request = new ProductReplacementRequest();
        request.setReplacementProductId(replacementProductId);
        request.setPriorityOrder(priorityOrder);
        request.setNotes(notes);
        request.setEffectiveAt(effectiveAt);
        return doAddReplacement(discontinuedProductId, request);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductLifecycleResponse.ReplacementOption> getReplacementProducts(@NonNull UUID productId) {
        findProduct(productId);
        return productReplacementRepository
            .findByOriginalProduct_IdAndDeletedAtIsNullOrderByPriorityOrderAsc(productId)
                .stream()
                .map(replacement -> ProductLifecycleResponse.ReplacementOption.builder()
                        .replacementId(replacement.getReplacementId())
                .replacementProductId(replacement.getReplacementProduct() == null
                    ? null
                    : replacement.getReplacementProduct().getId())
                        .priorityOrder(replacement.getPriorityOrder())
                        .notes(replacement.getNotes())
                        .effectiveAt(replacement.getEffectiveAt())
                        .build())
                .toList();
    }

    private ProductLifecycleResponse doUpdateLifecycle(UUID productId, ProductLifecycleUpdateRequest request) {
        if (request == null) {
            lifecycleUpdateDeniedCounter.increment();
            throw new CatalogValidationException("request is required");
        }
        if (request.getLifecycleState() == null) {
            lifecycleUpdateDeniedCounter.increment();
            throw new CatalogValidationException("lifecycleState is required");
        }
        if (request.getEffectiveAt() == null && request.getEffectiveDate() == null) {
            lifecycleUpdateDeniedCounter.increment();
            throw new CatalogValidationException("either effectiveAt or effectiveDate is required");
        }
        ProductEntity product = findProduct(productId);
        ProductLifecycleState currentState = resolveCurrentState(product);
        ProductLifecycleState nextState = request.getLifecycleState();
        UUID changedBy = resolveChangedByFromSecurityContext();

        if (currentState == nextState) {
            lifecycleUpdateDeniedCounter.increment();
            throw new CatalogValidationException("lifecycleState is already set to " + currentState);
        }

        if (currentState == ProductLifecycleState.DISCONTINUED
                && nextState != ProductLifecycleState.DISCONTINUED) {
            lifecycleUpdateDeniedCounter.increment();
            throw new CatalogBusinessRuleException(DISCONTINUED_REACTIVATION_ERROR);
        }

        boolean overridePermissionUsed = nextState == ProductLifecycleState.DISCONTINUED
                || currentState == ProductLifecycleState.DISCONTINUED;

        if (overridePermissionUsed && !hasAuthority("product:lifecycle:override_discontinued")) {
            lifecycleUpdateDeniedCounter.increment();
            throw new CatalogForbiddenOperationException(
                    "Missing required permission: product:lifecycle:override_discontinued");
        }

        if (overridePermissionUsed && isBlank(request.getOverrideReason())) {
            lifecycleUpdateDeniedCounter.increment();
            throw new CatalogValidationException(
                    "overrideReason is required when discontinued override permission is used");
        }

        Instant effectiveAt = resolveEffectiveAt(request.getEffectiveAt(), request.getEffectiveDate());
        if (effectiveAt.isBefore(currentInstant().minus(EFFECTIVE_AT_PAST_TOLERANCE))) {
            lifecycleUpdateDeniedCounter.increment();
            throw new CatalogValidationException("effectiveAt cannot be in the past");
        }

        product.setLifecycleState(nextState);
        product.setLifecycleStateEffectiveAt(effectiveAt);
        product.setLastStateChangedBy(changedBy);
        product.setLastStateChangedAt(currentInstant());
        product.setLifecycleOverrideReason(request.getOverrideReason());
        ProductEntity saved = productRepository.save(product);
        productDetailCacheInvalidationPublisher.invalidateProduct(saved.getId());

        lifecycleUpdateSuccessCounter.increment();
        log.info(
                "Product lifecycle updated: productId={}, oldState={}, newState={}, effectiveAt={}, changedBy={}, overridePermissionUsed={}",
                productId,
                currentState,
                nextState,
                effectiveAt,
                changedBy,
                overridePermissionUsed);

        List<ProductReplacementEntity> replacements = productReplacementRepository
            .findByOriginalProduct_IdAndDeletedAtIsNullOrderByPriorityOrderAsc(productId);
        return toResponse(saved, replacements);
    }

    private ProductLifecycleResponse.ReplacementOption doAddReplacement(UUID productId,
            ProductReplacementRequest request) {
        if (request == null || request.getReplacementProductId() == null) {
            throw new CatalogValidationException("replacementProductId is required");
        }
        if (productId.equals(request.getReplacementProductId())) {
            throw new CatalogValidationException("replacementProductId cannot be identical to productId");
        }
        if (request.getPriorityOrder() == null || request.getPriorityOrder() <= 0) {
            throw new CatalogValidationException("priorityOrder must be greater than zero");
        }

        ProductEntity originalProduct = findProduct(productId);
        if (resolveCurrentState(originalProduct) != ProductLifecycleState.DISCONTINUED) {
            throw new CatalogBusinessRuleException(
                    "Replacement products can only be added when lifecycleState is DISCONTINUED");
        }

        findProduct(request.getReplacementProductId());

        ProductReplacementEntity replacement = new ProductReplacementEntity();
        replacement.setOriginalProduct(originalProduct);
        replacement.setReplacementProduct(findProduct(request.getReplacementProductId()));
        replacement.setPriorityOrder(request.getPriorityOrder());
        replacement.setNotes(request.getNotes());
        replacement.setEffectiveAt(request.getEffectiveAt() != null ? request.getEffectiveAt() : currentInstant());

        ProductReplacementEntity saved = productReplacementRepository.save(replacement);
        productDetailCacheInvalidationPublisher.invalidateProduct(productId);

        return ProductLifecycleResponse.ReplacementOption.builder()
                .replacementId(saved.getReplacementId())
            .replacementProductId(saved.getReplacementProduct() == null ? null : saved.getReplacementProduct().getId())
                .priorityOrder(saved.getPriorityOrder())
                .notes(saved.getNotes())
                .effectiveAt(saved.getEffectiveAt())
                .build();
    }

    private ProductEntity findProduct(UUID productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new CatalogNotFoundException("Product not found: " + productId));
    }

    private UUID resolveChangedByFromSecurityContext() {
        String username = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        try {
            return UUID.fromString(username);
        } catch (IllegalArgumentException ignored) {
            // Keep actor identity deterministic even when principal is not UUID-formatted.
            return UUID.nameUUIDFromBytes(username.getBytes(StandardCharsets.UTF_8));
        }
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
        return currentInstant();
    }

    private Instant currentInstant() {
        return clock.instant();
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
                        .replacementProductId(replacement.getReplacementProduct() == null
                            ? null
                            : replacement.getReplacementProduct().getId())
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
