package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.service.AccountingCalendarZoneResolver;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Today for bank reconciliation, in the tenant's accounting calendar (#2558; Accounting Domain ruling: bank
 * reconciliation dates follow the accounting calendar). The intake port may depend only on {@code ..bankrec..}
 * (ArchUnit), so it reaches {@link AccountingCalendarZoneResolver} through this class; no bank-rec site resolves a
 * zone of its own. Without a zone it throws {@code AccountingTimeZoneUnsetException} (422).
 */
@Component
@RequiredArgsConstructor
public class BankRecCalendar {

    private final AccountingCalendarZoneResolver zoneResolver;

    /** Today in the bound tenant's accounting calendar. */
    public @NonNull LocalDate today() {
        return zoneResolver.today();
    }
}
