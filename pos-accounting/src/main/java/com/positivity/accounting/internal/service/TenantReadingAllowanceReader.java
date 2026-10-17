package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.BillIntakeReadingAllowance;
import com.positivity.accounting.internal.repository.BillIntakeReadingAllowanceRepository;
import com.positivity.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * The tenant's monthly document-reading allowance (CAP:550 S45, #2619; AW63, AW68), from the
 * {@code bill_intake_reading_allowance} replica of {@code tenant.events.v1}.
 *
 * <p>S26's meter (#2519) calls {@link #allowanceOf} and makes <b>no</b> provider call when it is empty: the draft
 * opens for manual entry with {@code EXTRACTION_ALLOWANCE_UNKNOWN}. That is fail closed, because the platform never
 * pays for reading it cannot attribute to an allowance; once the fact is consumed, reading resumes. Within a month a
 * new amount applies from {@link ReadingAllowance#effectiveAt()} on and month-to-date usage is unchanged; that
 * arithmetic is S26's, not here.
 *
 * <p>The table is tenant-scoped (row-level security), so {@link #allowanceOf} binds the tenant it is asked about for
 * the length of the read. Nothing is cached, so a change is visible on every instance at once.
 */
@Service
@RequiredArgsConstructor
public class TenantReadingAllowanceReader {

    private final BillIntakeReadingAllowanceRepository allowances;

    /** The stored allowance of {@code tenantId}, or empty when no fact has arrived for it. */
    public @NonNull Optional<ReadingAllowance> allowanceOf(@NonNull UUID tenantId) {
        return TenantContext.callAs(
                tenantId, () -> allowances.findById(tenantId).map(TenantReadingAllowanceReader::toAllowance));
    }

    private static ReadingAllowance toAllowance(BillIntakeReadingAllowance row) {
        return new ReadingAllowance(row.getTierCode(), row.getAmount(), row.getCurrency(), row.getEffectiveAt());
    }

    /**
     * One tenant's allowance.
     *
     * @param tierCode the reading tier's code
     * @param amount the allowance per calendar month
     * @param currencyCode ISO 4217 code of {@code amount}
     * @param effectiveAt when the allowance applies from
     */
    public record ReadingAllowance(
            @NonNull String tierCode,
            @NonNull BigDecimal amount,
            @NonNull String currencyCode,
            @NonNull Instant effectiveAt) {}
}
