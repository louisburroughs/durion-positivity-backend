package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.repository.BankOpeningBalanceRepository;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The dimensions a bank opening balance writes on each outstanding-item line (#2572, OI-10): the item's type,
 * reference and own date. The entry is dated the cutover, so bank reconciliation reads the item's own date from
 * here when the line is registered as an outstanding item.
 *
 * <p>A dimension is caller data on a manual entry, and a reversal copies its original's dimensions, so the date is
 * believed only on a line of an opening entry itself: one a {@code bank_opening_balance} row owns, that reverses
 * nothing, and only when the date is not after the entry's.
 */
@Component
@RequiredArgsConstructor
public class OpeningItemLineDimensions {

    /** {@code OUTSTANDING_CHECK} or {@code DEPOSIT_IN_TRANSIT}. */
    public static final String TYPE = "outstandingItemType";

    /** The check number or deposit reference. */
    public static final String REFERENCE = "reference";

    /** The date the check was written or the deposit made (ISO-8601). */
    public static final String ITEM_DATE = "itemDate";

    private final BankOpeningBalanceRepository openings;

    /**
     * The item's own date of a bank opening's item line, if {@code line} is one: its entry is an opening entry (a
     * {@code bank_opening_balance} row points at it) and not a reversal, and its {@code itemDate} reads as an
     * ISO-8601 date on or before the entry's date. Empty for any other line, forged dimensions included.
     */
    public @NonNull Optional<LocalDate> ownDate(@NonNull JournalEntryLine line) {
        JournalEntry entry = line.getJournalEntry();
        if (entry == null || entry.getTransactionDate() == null || entry.getReversalJournalEntryId() != null) {
            return Optional.empty();
        }
        LocalDate entryDate = entry.getTransactionDate().toLocalDate();
        Optional<LocalDate> own = itemDate(line.getDimensions()).filter(date -> !date.isAfter(entryDate));
        if (own.isEmpty() || !openings.existsByJournalEntryId(entry.getJournalEntryId())) {
            return Optional.empty();
        }
        return own;
    }

    /** The item date the dimensions of an opening item line carry, if they read as an ISO-8601 date. */
    public static @NonNull Optional<LocalDate> itemDate(@Nullable Map<String, String> dimensions) {
        if (dimensions == null || dimensions.get(TYPE) == null) {
            return Optional.empty();
        }
        String value = dimensions.get(ITEM_DATE);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
