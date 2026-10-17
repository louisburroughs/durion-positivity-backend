package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.bankrec.readmodel.BankPositionFacts;
import com.positivity.accounting.internal.bankrec.readmodel.BankPositionFacts.Facts;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.CashOutlookResponse;
import com.positivity.accounting.internal.dto.CashPositionResponse;
import com.positivity.accounting.internal.dto.CashPositionResponse.AccountRow;
import com.positivity.accounting.internal.dto.CashPositionResponse.Component;
import com.positivity.accounting.internal.dto.CashPositionResponse.NeedsAttention;
import com.positivity.accounting.internal.dto.CashPositionResponse.TimingItems;
import com.positivity.accounting.internal.dto.CashPositionResponse.UnpaidWalkInSales;
import com.positivity.accounting.internal.dto.UnpaidWalkInSalesResponse;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.enums.CashAttentionCode;
import com.positivity.accounting.internal.enums.CashAttentionReason;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.CashAccounts.Components;
import com.positivity.accounting.internal.service.CashAccounts.Row;
import com.positivity.accounting.internal.service.CashAttentionSources.AgedSession;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CashPositionService} over {@link CashAccounts} (the account-role rule and the balances), the bank
 * reconciliation read model, the undeposited-session and vendor-COD sources, the unpaid walk-in sales read and the
 * 30-day outlook.
 *
 * <p><b>Total.</b> In the bank plus waiting to be deposited plus kept in drawers; the statement figures and settlement
 * suspense are never part of it.
 *
 * <p><b>Needs attention</b>, in this order, each only when it applies: {@code SETTLEMENT_SUSPENSE_NOT_ZERO} (the
 * suspense balance, credit positive); {@code UNDEPOSITED_CASH_AGED} (undeposited sessions older than the tenant's
 * threshold, their bank drops and oldest business date; the aged sessions that do not balance are a second item with
 * reason {@code UNBALANCED}); {@code CLEARING_RESIDUAL_NOT_ZERO} (the clearing balance, debit positive, while no
 * session waits to be deposited); {@code VENDOR_COD_UNAPPLIED_AGED} (the unapplied cash-on-delivery money and the oldest
 * payment date); {@code BELOW_SAFETY_CUSHION} (the outlook's low point and its day, when strictly below the cushion).
 */
@Service
@Transactional(readOnly = true)
public class CashPositionServiceImpl implements CashPositionService {

    private final CashAccounts cashAccounts;
    private final BankPositionFacts bankPositionFacts;
    private final CashAttentionSources attentionSources;
    private final CashOutlookService outlookService;
    private final UnpaidWalkInSalesService unpaidWalkInSalesService;
    private final InvoiceBalanceCalculator invoiceBalanceCalculator;
    private final ExtInvoiceRepository extInvoiceRepository;
    private final CashAndPayablesSettings settings;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final LedgerCurrency ledgerCurrency;
    private final Clock clock;
    private final Duration factLag;

    public CashPositionServiceImpl(
            CashAccounts cashAccounts,
            BankPositionFacts bankPositionFacts,
            CashAttentionSources attentionSources,
            CashOutlookService outlookService,
            UnpaidWalkInSalesService unpaidWalkInSalesService,
            InvoiceBalanceCalculator invoiceBalanceCalculator,
            ExtInvoiceRepository extInvoiceRepository,
            CashAndPayablesSettings settings,
            AccountingCalendarZoneResolver zoneResolver,
            LedgerCurrency ledgerCurrency,
            Clock clock,
            @Value("${pos.accounting.cash-position.fact-lag:PT15M}") Duration factLag) {
        this.cashAccounts = cashAccounts;
        this.bankPositionFacts = bankPositionFacts;
        this.attentionSources = attentionSources;
        this.outlookService = outlookService;
        this.unpaidWalkInSalesService = unpaidWalkInSalesService;
        this.invoiceBalanceCalculator = invoiceBalanceCalculator;
        this.extInvoiceRepository = extInvoiceRepository;
        this.settings = settings;
        this.zoneResolver = zoneResolver;
        this.ledgerCurrency = ledgerCurrency;
        this.clock = clock;
        this.factLag = factLag;
    }

    @Override
    public @NonNull CashPositionResponse read() {
        Instant now = clock.instant();
        String currency = ledgerCurrency.code();
        LocalDate today = zoneResolver.today();
        Components cash = cashAccounts.read();

        boolean statementFields = SecurityContextHelper.isAuthenticated()
                && SecurityContextHelper.hasAuthority(AccountingPermissions.RECONCILIATION_VIEW);
        Map<UUID, Facts> facts = statementFields
                ? bankPositionFacts.factsOf(
                        cash.bank().stream().map(Row::glAccountId).toList())
                : Map.of();

        BigDecimal bankTotal = Components.total(cash.bank());
        BigDecimal waitingTotal = Components.total(cash.waiting());
        BigDecimal drawersTotal = Components.total(cash.drawers());

        UnpaidWalkInSalesResponse walkIn = unpaidWalkInSalesService.read();
        Instant eventFedAsOf = newestWalkInFact();
        boolean lagging = eventFedAsOf != null && eventFedAsOf.isBefore(now.minus(factLag));

        return new CashPositionResponse(
                now,
                eventFedAsOf,
                lagging,
                currency,
                scaled(bankTotal.add(waitingTotal).add(drawersTotal), currency),
                component(cash.bank(), facts, statementFields, currency),
                component(cash.waiting(), Map.of(), false, currency),
                component(cash.drawers(), Map.of(), false, currency),
                cash.excludedForeignCurrencyAccounts(),
                needsAttention(cash, today, currency),
                walkIn.getBalance().signum() != 0 || !walkIn.isHouseAccountKnown()
                        ? new UnpaidWalkInSales(
                                walkIn.getBalance(),
                                walkIn.getOpenInvoices() == null
                                        ? 0
                                        : walkIn.getOpenInvoices().size(),
                                walkIn.isHouseAccountKnown())
                        : null);
    }

    private static Component component(
            List<Row> rows, Map<UUID, Facts> facts, boolean statementFields, String currency) {
        List<AccountRow> accounts = rows.stream()
                .map(row -> accountRow(
                        row, statementFields ? facts.get(row.glAccountId()) : null, statementFields, currency))
                .toList();
        return new Component(scaled(Components.total(rows), currency), accounts);
    }

    private static AccountRow accountRow(Row row, @Nullable Facts facts, boolean statementFields, String currency) {
        if (!statementFields || facts == null) {
            return new AccountRow(
                    row.glAccountId(),
                    row.accountCode(),
                    row.accountName(),
                    row.balance(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        return new AccountRow(
                row.glAccountId(),
                row.accountCode(),
                row.accountName(),
                row.balance(),
                facts.statementClosingBalance() == null ? null : scaled(facts.statementClosingBalance(), currency),
                facts.statementEndDate(),
                facts.reconciledThrough(),
                timing(facts.depositsInTransit(), currency),
                timing(facts.outstandingChecks(), currency),
                facts.unmatchedBankLineCount());
    }

    private static TimingItems timing(BankPositionFacts.ItemTotal total, String currency) {
        return new TimingItems(total.count(), scaled(total.amount(), currency));
    }

    private List<NeedsAttention> needsAttention(Components cash, LocalDate today, String currency) {
        CashAndPayablesSettings.Settings thresholds = settings.settings();
        List<NeedsAttention> items = new ArrayList<>();

        if (cash.suspenseBalance().signum() != 0) {
            // Settlement suspense is a liability: shown with the credit as positive money owed to be matched.
            items.add(new NeedsAttention(
                    CashAttentionCode.SETTLEMENT_SUSPENSE_NOT_ZERO,
                    null,
                    cash.suspenseBalance().negate(),
                    null,
                    null));
        }

        List<AgedSession> waiting = attentionSources.undepositedSessions();
        List<AgedSession> aged = CashAttentionSources.aged(waiting, today, thresholds.undepositedCashAgedDays());
        agedSessionsItem(aged.stream().filter(AgedSession::balanced).toList(), null, currency)
                .ifPresent(items::add);
        agedSessionsItem(
                        aged.stream().filter(session -> !session.balanced()).toList(),
                        CashAttentionReason.UNBALANCED,
                        currency)
                .ifPresent(items::add);

        BigDecimal clearing = cash.clearingBalance();
        if (clearing.signum() != 0 && waiting.isEmpty()) {
            items.add(new NeedsAttention(CashAttentionCode.CLEARING_RESIDUAL_NOT_ZERO, null, clearing, null, null));
        }

        List<APPayment> cod = attentionSources.agedUnappliedVendorCod(today, thresholds.vendorCodUnappliedAgedDays());
        if (!cod.isEmpty()) {
            BigDecimal unapplied =
                    cod.stream().map(APPayment::getUnappliedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            LocalDate since = cod.stream()
                    .map(APPayment::getPaymentDate)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            items.add(new NeedsAttention(
                    CashAttentionCode.VENDOR_COD_UNAPPLIED_AGED, null, scaled(unapplied, currency), cod.size(), since));
        }

        CashOutlookResponse outlook = outlookService.outlook(CashOutlookService.DEFAULT_DAYS, cash);
        if (outlook.belowCushion()) {
            items.add(new NeedsAttention(
                    CashAttentionCode.BELOW_SAFETY_CUSHION,
                    null,
                    outlook.lowPoint().balance(),
                    null,
                    outlook.lowPoint().date()));
        }
        return List.copyOf(items);
    }

    private static Optional<NeedsAttention> agedSessionsItem(
            List<AgedSession> sessions, @Nullable CashAttentionReason reason, String currency) {
        if (sessions.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal amount = sessions.stream().map(AgedSession::depositAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDate since = sessions.stream()
                .map(AgedSession::businessDate)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        return Optional.of(new NeedsAttention(
                CashAttentionCode.UNDEPOSITED_CASH_AGED, reason, scaled(amount, currency), sessions.size(), since));
    }

    /** The newest fact the unpaid walk-in balance rests on: the latest replica update of a CASH party's invoices. */
    private @Nullable Instant newestWalkInFact() {
        Set<UUID> parties = invoiceBalanceCalculator.walkInPartyIds();
        if (parties.isEmpty()) {
            return null;
        }
        return extInvoiceRepository.findNewestUpdateByPartyIdIn(
                parties.stream().map(UUID::toString).collect(Collectors.toSet()));
    }

    private static BigDecimal scaled(BigDecimal amount, String currency) {
        return InvoiceBalanceCalculator.atCurrencyScale(amount, currency);
    }
}
