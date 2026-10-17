package com.positivity.domainevents.tenant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * {@code tenant.reading-allowance.changed} v1 (CAP:550 S45, #2619; AW63, AW68): the monthly document-reading allowance
 * a tenant is entitled to, from the reading tier a platform operator assigned it. Keyed by tenant id on {@code
 * tenant.events.v1}; the consumer (pos-accounting) keeps the latest allowance per tenant, guarded by the envelope's
 * {@code aggregateVersion}, and never pays for reading it cannot attribute to an allowance.
 *
 * <p>Emitted when the tenant is created (on {@code STANDARD}, 20.00 USD by default), when its tier is assigned, and
 * for every tenant on a tier whose amount changes. Every amount carries its currency (ADR-0067 PC-3). Not part of the
 * public tenant projection: commercial terms stay out of what every module replicates.
 *
 * @param tenantId the tenant the allowance belongs to
 * @param tierCode the reading tier's code (for example {@code STANDARD})
 * @param monthlyAllowance the allowance per calendar month
 * @param effectiveAt when the allowance applies from (the assignment or the tier change)
 */
public record TenantReadingAllowanceV1(
        @NonNull UUID tenantId,
        @NonNull String tierCode,
        @NonNull MonthlyAllowance monthlyAllowance,
        @NonNull Instant effectiveAt) {

    public static final String EVENT_TYPE = TenantEventTypes.READING_ALLOWANCE_CHANGED;
    public static final int SCHEMA_VERSION = 1;

    public TenantReadingAllowanceV1 {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (tierCode == null || tierCode.isBlank()) {
            throw new IllegalArgumentException("tierCode must not be blank");
        }
        if (monthlyAllowance == null) {
            throw new IllegalArgumentException("monthlyAllowance must not be null");
        }
        if (effectiveAt == null) {
            throw new IllegalArgumentException("effectiveAt must not be null");
        }
    }

    /**
     * An amount with its currency (ADR-0067 PC-3).
     *
     * @param amount the allowance, at least zero, four decimals at most
     * @param currencyCode ISO 4217 code
     */
    public record MonthlyAllowance(@NonNull BigDecimal amount, @NonNull String currencyCode) {
        public MonthlyAllowance {
            if (amount == null || amount.signum() < 0) {
                throw new IllegalArgumentException("amount must be at least zero");
            }
            if (currencyCode == null || !currencyCode.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("currencyCode must be an ISO 4217 code");
            }
        }
    }
}
