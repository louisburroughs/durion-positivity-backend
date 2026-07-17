package com.positivity.warranty.internal.service;

import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import com.positivity.warranty.internal.dto.RegistrationRequest;
import com.positivity.warranty.internal.entity.WarrantyPolicy;
import com.positivity.warranty.internal.enums.AppliesToType;
import com.positivity.warranty.internal.enums.RegistrationStatus;
import com.positivity.warranty.internal.repository.WarrantyPolicyRepository;
import com.positivity.warranty.internal.repository.WarrantyRegistrationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements issue #925: candidates are loaded by (autoRegister, appliesToType=PRODUCT_LIST)
 * and product membership plus the effectivity window are filtered in Java (jsonb containment
 * is not portable). Creation goes through {@link RegistrationService#create} so validation
 * stays in one place; that path needs no authenticated user (JPA auditing falls back to
 * {@code system}), which suits this consumer-driven flow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoRegistrationServiceImpl implements AutoRegistrationService {

    private final WarrantyPolicyRepository policyRepository;
    private final WarrantyRegistrationRepository registrationRepository;
    private final RegistrationService registrationService;
    private final Clock clock;

    @Override
    @Transactional
    public int registerFromWorkorderSale(@NonNull WorkorderUpdatedV1 event) {
        if (event.invoiceId() == null) {
            // Invoice not generated yet — the sale has not concluded.
            return 0;
        }
        if (event.customerId() == null) {
            log.warn(
                    "Skipping auto-registration for workorder {}: invoiced but no customerId on event",
                    event.workorderId());
            return 0;
        }
        Set<UUID> soldProductIds = soldProductIds(event);
        if (soldProductIds.isEmpty()) {
            return 0;
        }
        LocalDate purchaseDate = purchaseDate(event);
        List<WarrantyPolicy> candidates =
                policyRepository.findByAutoRegisterTrueAndAppliesToType(AppliesToType.PRODUCT_LIST);
        int created = 0;
        for (WarrantyPolicy policy : candidates) {
            if (!coversAnySoldProduct(policy, soldProductIds) || !effectiveOn(policy, purchaseDate)) {
                continue;
            }
            if (registrationRepository.existsByPolicyIdAndSourceInvoiceId(policy.getId(), event.invoiceId())) {
                continue;
            }
            registrationService.create(new RegistrationRequest(
                    policy.getId(),
                    event.customerId(),
                    null,
                    event.invoiceId(),
                    // Workorder facts carry no invoice line ids — accepted v1 gap.
                    null,
                    null,
                    purchaseDate,
                    expiresAt(policy, purchaseDate),
                    RegistrationStatus.ACTIVE));
            created++;
            log.info(
                    "Auto-registered warranty policy {} for customer {} from invoice {} (workorder {})",
                    policy.getId(),
                    event.customerId(),
                    event.invoiceId(),
                    event.workorderId());
        }
        return created;
    }

    @NonNull
    private static Set<UUID> soldProductIds(@NonNull WorkorderUpdatedV1 event) {
        if (event.parts() == null) {
            return Set.of();
        }
        Set<UUID> productIds = new LinkedHashSet<>();
        event.parts().stream()
                .map(WorkorderUpdatedV1.PartLine::productEntityId)
                .filter(Objects::nonNull)
                .forEach(productIds::add);
        return productIds;
    }

    private static boolean coversAnySoldProduct(@NonNull WarrantyPolicy policy, @NonNull Set<UUID> soldProductIds) {
        List<UUID> covered = policy.getAppliesToProductEntityIds();
        if (covered == null || covered.isEmpty()) {
            return false;
        }
        return covered.stream().anyMatch(soldProductIds::contains);
    }

    /** Null-tolerant effectivity window: an unset bound never excludes. */
    private static boolean effectiveOn(@NonNull WarrantyPolicy policy, @NonNull LocalDate purchaseDate) {
        if (policy.getEffectiveFrom() != null && policy.getEffectiveFrom().isAfter(purchaseDate)) {
            return false;
        }
        return policy.getEffectiveTo() == null || !policy.getEffectiveTo().isBefore(purchaseDate);
    }

    /** Sale date = the fact's updatedAt at UTC (falls back to createdAt, then the clock). */
    @NonNull
    private LocalDate purchaseDate(@NonNull WorkorderUpdatedV1 event) {
        Instant saleInstant = event.updatedAt() != null
                ? event.updatedAt()
                : (event.createdAt() != null ? event.createdAt() : Instant.now(clock));
        return saleInstant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static LocalDate expiresAt(@NonNull WarrantyPolicy policy, @NonNull LocalDate purchaseDate) {
        return policy.getDurationMonths() == null ? null : purchaseDate.plusMonths(policy.getDurationMonths());
    }
}
