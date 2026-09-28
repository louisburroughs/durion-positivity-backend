package com.positivity.order.internal.service;

import com.positivity.order.internal.entity.OrderPaymentRecord;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Net settled funds per payment intent on an order's ledger (Σ SETTLED − Σ REVERSED), with the
 * currency the intent settled in. Shared by the cancellation and return sagas, which refund these
 * entries (spec R4.6, R5.3).
 */
final class NetSettledPayments {

    /**
     * @param amount positive net settled amount
     * @param currencyCode ISO 4217 code carried by the intent's ledger entries (ADR-0067 DF-3); null
     *     when the entries state none or disagree, in which case no reversal may be built from it
     */
    record NetSettlement(
            @NonNull BigDecimal amount, @Nullable String currencyCode) {}

    private NetSettledPayments() {}

    /** Positive entries only, in ledger order. */
    static @NonNull Map<UUID, NetSettlement> byIntent(@NonNull List<OrderPaymentRecord> records) {
        Map<UUID, BigDecimal> net = new LinkedHashMap<>();
        Map<UUID, String> currency = new LinkedHashMap<>();
        Map<UUID, Boolean> ambiguous = new LinkedHashMap<>();
        for (OrderPaymentRecord record : records) {
            UUID intentId = record.getPaymentIntentId();
            if (intentId == null) {
                continue;
            }
            BigDecimal signed = record.getRecordType() == OrderPaymentRecord.RecordType.SETTLED
                    ? record.getAmount()
                    : record.getAmount().negate();
            net.merge(intentId, signed, BigDecimal::add);
            String code = record.getCurrencyCode();
            if (code == null) {
                ambiguous.put(intentId, true);
            } else {
                String seen = currency.putIfAbsent(intentId, code);
                if (seen != null && !seen.equals(code)) {
                    ambiguous.put(intentId, true);
                }
            }
        }
        Map<UUID, NetSettlement> result = new LinkedHashMap<>();
        net.forEach((intentId, amount) -> {
            if (amount.signum() > 0) {
                String code = Objects.equals(ambiguous.get(intentId), Boolean.TRUE) ? null : currency.get(intentId);
                result.put(intentId, new NetSettlement(amount, code));
            }
        });
        return result;
    }
}
