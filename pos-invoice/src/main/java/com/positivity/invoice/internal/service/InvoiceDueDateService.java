package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.entity.BillingRules;
import com.positivity.invoice.internal.entity.ExtCustomerPartyReplica;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.PaymentTerms;
import com.positivity.invoice.internal.repository.BillingRulesRepository;
import com.positivity.invoice.internal.repository.ExtCustomerPartyReplicaRepository;
import com.positivity.invoice.internal.repository.ExtLocationReplicaRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Computes the collections-aging due date frozen at invoice finalization (#993).
 *
 * <p>Policy (issue #993 decision record):
 *
 * <ul>
 *   <li><b>Individual bill-to party</b> (any non-{@code COMMERCIAL} type): due on receipt —
 *       the due date IS the finalization date.
 *   <li><b>Commercial bill-to party</b>: finalization date + the payment-terms rule, resolved as
 *       the party's {@link BillingRules#getPaymentTermsCode()} → else the global default
 *       ({@code billing.defaults.payment-terms-code}, {@code NET_30}).
 *   <li>Party type unresolvable at finalization → {@code DUE_ON_RECEIPT} (degrades to issue-date
 *       aging, the safe default). Same for a party id that is not a UUID (replica keys are UUIDs).
 * </ul>
 *
 * <p>The anchor is the finalization instant converted to a business {@link LocalDate} in the
 * invoice location's timezone ({@code ext_location.timezone}); when the location or its timezone
 * is unknown, UTC. Net terms add calendar days — aging buckets are calendar-based. The precedence
 * chain deliberately leaves room for a future per-invoice negotiated override:
 * invoice override (future) → party rules → global default.
 */
@Slf4j
@Service
public class InvoiceDueDateService {

    private final ExtCustomerPartyReplicaRepository extCustomerPartyReplicaRepository;
    private final BillingRulesRepository billingRulesRepository;
    private final ExtLocationReplicaRepository extLocationReplicaRepository;
    private final String defaultPaymentTermsCode;

    public InvoiceDueDateService(
            @NonNull ExtCustomerPartyReplicaRepository extCustomerPartyReplicaRepository,
            @NonNull BillingRulesRepository billingRulesRepository,
            @NonNull ExtLocationReplicaRepository extLocationReplicaRepository,
            @Value("${billing.defaults.payment-terms-code:NET_30}") @NonNull String defaultPaymentTermsCode) {
        this.extCustomerPartyReplicaRepository = extCustomerPartyReplicaRepository;
        this.billingRulesRepository = billingRulesRepository;
        this.extLocationReplicaRepository = extLocationReplicaRepository;
        this.defaultPaymentTermsCode = defaultPaymentTermsCode;
    }

    /** The resolved rule and the per-invoice date it generates. */
    public record DueTerms(
            @NonNull PaymentTerms paymentTerms, @NonNull LocalDate dueDate) {}

    /**
     * Resolves the payment terms for the invoice's bill-to party and computes the due date from
     * the given finalization instant. Pure computation over replicas + rules — never mutates.
     */
    @NonNull
    public DueTerms resolve(@NonNull Invoice invoice, @NonNull Instant finalizedAt) {
        PaymentTerms terms = resolveTerms(invoice.getPartyId());
        LocalDate finalizationDate =
                finalizedAt.atZone(resolveZone(invoice.getLocationId())).toLocalDate();
        return new DueTerms(terms, finalizationDate.plusDays(terms.netDays()));
    }

    @NonNull
    private PaymentTerms resolveTerms(@Nullable String partyId) {
        String partyType = lookupPartyType(partyId);
        if (partyType == null) {
            // Unresolvable bill-to party — degrade to issue-date aging, the safe default.
            log.debug("Party type unresolvable for partyId(hash)={}; defaulting to DUE_ON_RECEIPT", hash(partyId));
            return PaymentTerms.DUE_ON_RECEIPT;
        }
        if (!"COMMERCIAL".equalsIgnoreCase(partyType)) {
            return PaymentTerms.DUE_ON_RECEIPT;
        }
        return billingRulesRepository
                .findByPartyId(partyId)
                .map(BillingRules::getPaymentTermsCode)
                .flatMap(PaymentTerms::fromCode)
                .orElseGet(() -> PaymentTerms.fromCode(defaultPaymentTermsCode).orElse(PaymentTerms.NET_30));
    }

    @Nullable
    private String lookupPartyType(@Nullable String partyId) {
        if (partyId == null || partyId.isBlank()) {
            return null;
        }
        UUID partyUuid;
        try {
            partyUuid = UUID.fromString(partyId);
        } catch (IllegalArgumentException _) {
            return null;
        }
        return extCustomerPartyReplicaRepository
                .findById(partyUuid)
                .map(ExtCustomerPartyReplica::getPartyType)
                .orElse(null);
    }

    @NonNull
    private ZoneId resolveZone(@Nullable UUID locationId) {
        if (locationId == null) {
            return ZoneOffset.UTC;
        }
        String timezone = extLocationReplicaRepository
                .findById(locationId)
                .map(replica -> replica.getTimezone())
                .orElse(null);
        if (timezone == null || timezone.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            log.warn("Invalid replica timezone '{}' for locationId={}; falling back to UTC", timezone, locationId);
            return ZoneOffset.UTC;
        }
    }

    private static int hash(@Nullable String value) {
        return value == null ? 0 : value.hashCode();
    }
}
