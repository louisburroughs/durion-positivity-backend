package com.positivity.accounting.internal.billintake.service;

import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.service.AccountingCalendarZoneResolver;
import com.positivity.accounting.internal.service.AccountingSequenceLocker;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Draws the business reference of a bill draft: {@code BI-YYYYMM-nnnnnnn}, from the bound tenant's own {@code
 * accounting_sequence} counter, the month in the tenant's accounting calendar. Must run in the draft's transaction: the
 * counter row stays locked until it ends, so the number is drawn as late as it can be.
 */
@Component
@RequiredArgsConstructor
public class BillIntakeReferences {

    static final String SCOPE_PREFIX = "BI-";

    private final AccountingSequenceLocker sequenceLocker;
    private final AccountingCalendarZoneResolver zoneResolver;

    public @NonNull String next() {
        LocalDate recorded = zoneResolver.today();
        String period = String.format("%04d%02d", recorded.getYear(), recorded.getMonthValue());
        AccountingSequence sequence = sequenceLocker.lockOrProvision(SCOPE_PREFIX + period);
        long assigned = sequence.getNextValue();
        sequence.setNextValue(assigned + 1);
        return String.format("%s%s-%07d", SCOPE_PREFIX, period, assigned);
    }
}
