package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.UnpaidWalkInSalesResponse;
import com.positivity.accounting.internal.dto.WalkInNeedsAttention;
import com.positivity.accounting.internal.dto.WalkInOpenInvoice;
import com.positivity.accounting.internal.dto.WalkInUnappliedPayment;
import com.positivity.accounting.internal.entity.ExtCustomerParty;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ExtLocationReplica;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import com.positivity.accounting.internal.enums.WalkInResolution;
import com.positivity.accounting.internal.repository.ExtCustomerPartyRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.ExtLocationReplicaRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link UnpaidWalkInSalesService} over accounting's own replicas and the shared receivable rules of
 * {@link InvoiceBalanceCalculator} (the open-invoice rule, the batch balance and the currency scale),
 * so this read and aged receivables cannot drift: aged receivables plus this read's balance is the AR
 * subledger, apart from party-less legacy invoices.
 *
 * <p><strong>Business day.</strong> A walk-in invoice's sale date is the local date of {@code
 * finalizedAt} (else {@code invoiceCreatedAt}) in its location's time zone; its business day has ended
 * when that date is before the location's current local date on the injected {@link Clock}. A location
 * without a usable time zone, or an invoice without a location, uses UTC and is marked {@code
 * timezoneFallback}; the fallback is logged once per location.
 *
 * <p>A fixed number of queries per read, whatever the number of invoices: the CASH parties, their
 * invoices, five balance terms, their locations, their available payments and those payments' invoices.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class UnpaidWalkInSalesServiceImpl implements UnpaidWalkInSalesService {

    /** Until OI-5 decides reassignment, a walk-in sale is resolved by collecting or by a credit memo. */
    static final List<WalkInResolution> RESOLUTIONS = List.of(WalkInResolution.COLLECT, WalkInResolution.CREDIT_MEMO);

    private static final String NO_LOCATION = "no-location";

    private final ExtCustomerPartyRepository customerPartyRepository;
    private final ExtInvoiceRepository extInvoiceRepository;
    private final ExtLocationReplicaRepository locationRepository;
    private final ReceivablePaymentRepository receivablePaymentRepository;
    private final InvoiceBalanceCalculator invoiceBalanceCalculator;
    private final LedgerCurrency ledgerCurrency;
    private final Clock clock;

    /** Locations whose UTC fallback was already logged by this instance. */
    private final Set<String> fallbackLogged = ConcurrentHashMap.newKeySet();

    public UnpaidWalkInSalesServiceImpl(
            ExtCustomerPartyRepository customerPartyRepository,
            ExtInvoiceRepository extInvoiceRepository,
            ExtLocationReplicaRepository locationRepository,
            ReceivablePaymentRepository receivablePaymentRepository,
            InvoiceBalanceCalculator invoiceBalanceCalculator,
            LedgerCurrency ledgerCurrency,
            Clock clock) {
        this.customerPartyRepository = customerPartyRepository;
        this.extInvoiceRepository = extInvoiceRepository;
        this.locationRepository = locationRepository;
        this.receivablePaymentRepository = receivablePaymentRepository;
        this.invoiceBalanceCalculator = invoiceBalanceCalculator;
        this.ledgerCurrency = ledgerCurrency;
        this.clock = clock;
    }

    @Override
    public @NonNull UnpaidWalkInSalesResponse read() {
        Instant now = clock.instant();
        String currency = ledgerCurrency.code();
        List<ExtCustomerParty> parties =
                customerPartyRepository.findByHouseAccount(CustomerPartyUpdatedV1.HOUSE_ACCOUNT_CASH_SALE).stream()
                        .sorted(Comparator.comparing(ExtCustomerParty::getPartyId))
                        .toList();
        if (parties.isEmpty()) {
            // S7's flag has not reached this replica yet (no party-fact replay): zero, but not vouched for.
            return UnpaidWalkInSalesResponse.builder()
                    .asOf(now)
                    .houseAccountKnown(false)
                    .currencyCode(currency)
                    .balance(InvoiceBalanceCalculator.atCurrencyScale(BigDecimal.ZERO, currency))
                    .openInvoices(List.of())
                    .needsAttention(new WalkInNeedsAttention(
                            0, InvoiceBalanceCalculator.atCurrencyScale(BigDecimal.ZERO, currency)))
                    .unappliedPayments(List.of())
                    .build();
        }
        if (parties.size() > 1) {
            log.warn(
                    "{} parties carry the CASH house-account flag; pos-customer keeps one per tenant. Reading all of"
                            + " them: {}",
                    parties.size(),
                    parties.stream().map(ExtCustomerParty::getPartyId).toList());
        }
        ExtCustomerParty account = parties.getFirst();
        Set<UUID> partyIds = parties.stream().map(ExtCustomerParty::getPartyId).collect(Collectors.toSet());

        List<WalkInOpenInvoice> openInvoices = openInvoices(partyIds, now, currency);
        BigDecimal balance = openInvoices.stream()
                .map(WalkInOpenInvoice::getBalanceDue)
                .reduce(InvoiceBalanceCalculator.atCurrencyScale(BigDecimal.ZERO, currency), BigDecimal::add);
        List<WalkInOpenInvoice> ended = openInvoices.stream()
                .filter(WalkInOpenInvoice::isBusinessDayEnded)
                .toList();
        BigDecimal endedAmount = ended.stream()
                .map(WalkInOpenInvoice::getBalanceDue)
                .reduce(InvoiceBalanceCalculator.atCurrencyScale(BigDecimal.ZERO, currency), BigDecimal::add);

        return UnpaidWalkInSalesResponse.builder()
                .asOf(now)
                .houseAccountKnown(true)
                .customerNumber(account.getCustomerNumber())
                .customerId(account.getPartyId())
                .currencyCode(currency)
                .balance(balance)
                .openInvoices(openInvoices)
                .needsAttention(new WalkInNeedsAttention(ended.size(), endedAmount))
                .unappliedPayments(unappliedPayments(partyIds, currency))
                .build();
    }

    private List<WalkInOpenInvoice> openInvoices(Set<UUID> partyIds, Instant now, String currency) {
        List<String> parties = partyIds.stream().map(UUID::toString).toList();
        List<ExtInvoice> candidates =
                extInvoiceRepository.findByPartyIdInAndStatusIn(parties, InvoiceBalanceCalculator.AR_ELIGIBLE_STATUSES);
        Map<UUID, BigDecimal> balances = invoiceBalanceCalculator.balancesDue(candidates);
        List<ExtInvoice> open = candidates.stream()
                .filter(invoice -> InvoiceBalanceCalculator.isOpenReceivable(
                        invoice, balances.getOrDefault(invoice.getInvoiceId(), BigDecimal.ZERO), currency))
                .toList();

        Set<UUID> locationIds = open.stream()
                .map(ExtInvoice::getLocationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, ExtLocationReplica> locations = locationIds.isEmpty()
                ? Map.of()
                : locationRepository.findAllById(locationIds).stream()
                        .collect(Collectors.toMap(ExtLocationReplica::getLocationId, Function.identity()));

        List<SaleRow> rows = new ArrayList<>();
        for (ExtInvoice invoice : open) {
            ExtLocationReplica location =
                    invoice.getLocationId() == null ? null : locations.get(invoice.getLocationId());
            ZoneId zone = zoneOf(invoice, location);
            boolean fallback = zone == null;
            ZoneId effective = fallback ? ZoneOffset.UTC : zone;
            Instant saleInstant = saleInstant(invoice);
            LocalDate saleDate = LocalDate.ofInstant(saleInstant, effective);
            LocalDate today = LocalDate.ofInstant(now, effective);
            rows.add(new SaleRow(
                    saleInstant,
                    WalkInOpenInvoice.builder()
                            .invoiceNumber(invoice.getInvoiceNumber())
                            .locationCode(location == null ? null : location.getCode())
                            .saleDate(saleDate)
                            .total(InvoiceBalanceCalculator.atCurrencyScale(
                                    invoice.getTotal() == null ? BigDecimal.ZERO : invoice.getTotal(), currency))
                            .balanceDue(InvoiceBalanceCalculator.atCurrencyScale(
                                    balances.get(invoice.getInvoiceId()), currency))
                            .businessDayEnded(saleDate.isBefore(today))
                            .timezoneFallback(fallback)
                            .resolutions(RESOLUTIONS)
                            .invoiceId(invoice.getInvoiceId())
                            .locationId(invoice.getLocationId())
                            .build()));
        }
        // Oldest sale first, ties by invoice id.
        rows.sort(Comparator.comparing(SaleRow::saleInstant)
                .thenComparing(row -> row.invoice().getInvoiceId()));
        return rows.stream().map(SaleRow::invoice).toList();
    }

    private List<WalkInUnappliedPayment> unappliedPayments(Set<UUID> partyIds, String currency) {
        List<ReceivablePayment> payments = receivablePaymentRepository
                .findByCustomerIdInAndStatusOrderByClearedAtAscPaymentIdAsc(partyIds, ReceivablePaymentStatus.AVAILABLE)
                .stream()
                .filter(payment -> payment.getUnappliedAmount() != null
                        && payment.getUnappliedAmount().signum() > 0)
                .toList();
        Set<UUID> sourceInvoiceIds = payments.stream()
                .map(ReceivablePayment::getSourceInvoiceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> invoiceNumbers = sourceInvoiceIds.isEmpty()
                ? Map.of()
                : extInvoiceRepository.findAllById(sourceInvoiceIds).stream()
                        .filter(invoice -> invoice.getInvoiceNumber() != null)
                        .collect(Collectors.toMap(ExtInvoice::getInvoiceId, ExtInvoice::getInvoiceNumber));
        return payments.stream()
                .map(payment -> WalkInUnappliedPayment.builder()
                        .paymentReference(
                                payment.getSourceInvoiceId() == null
                                        ? null
                                        : invoiceNumbers.get(payment.getSourceInvoiceId()))
                        .receivedAt(payment.getClearedAt())
                        .unappliedAmount(
                                InvoiceBalanceCalculator.atCurrencyScale(payment.getUnappliedAmount(), currency))
                        .paymentId(payment.getPaymentId())
                        .build())
                .toList();
    }

    /** The sale instant: {@code finalizedAt}, else {@code invoiceCreatedAt}, else the replica's last update. */
    static @NonNull Instant saleInstant(@NonNull ExtInvoice invoice) {
        if (invoice.getFinalizedAt() != null) {
            return invoice.getFinalizedAt();
        }
        if (invoice.getInvoiceCreatedAt() != null) {
            return invoice.getInvoiceCreatedAt();
        }
        return invoice.getUpdatedAt();
    }

    /** The location's time zone, or null when there is none to use (logged once per location). */
    private @Nullable ZoneId zoneOf(ExtInvoice invoice, @Nullable ExtLocationReplica location) {
        String timezone = location == null ? null : location.getTimezone();
        if (timezone != null && !timezone.isBlank()) {
            try {
                return ZoneId.of(timezone.trim());
            } catch (DateTimeException e) {
                logFallback(location.getLocationId().toString(), "its time zone '" + timezone + "' is not valid");
                return null;
            }
        }
        if (invoice.getLocationId() == null) {
            logFallback(NO_LOCATION, "walk-in invoices without a location");
        } else {
            logFallback(
                    invoice.getLocationId().toString(),
                    location == null ? "the location is not in the replica" : "the location has no time zone");
        }
        return null;
    }

    private void logFallback(String key, String reason) {
        if (fallbackLogged.add(key)) {
            log.warn("Walk-in business day computed in UTC for location {}: {}", key, reason);
        }
    }

    private record SaleRow(Instant saleInstant, WalkInOpenInvoice invoice) {}
}
