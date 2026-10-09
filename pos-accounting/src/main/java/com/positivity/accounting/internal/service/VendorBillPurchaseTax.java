package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.client.TaxReferenceClient;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.config.PurchasePlace;
import com.positivity.accounting.internal.config.TaxCountry;
import com.positivity.accounting.internal.dto.TaxPurchaseRules;
import com.positivity.accounting.internal.dto.TaxUseQuote;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A vendor bill's purchase tax (CAP:550 S43, AW44): the tax country's purchase-tax rules, read from pos-tax, and the
 * self-assessed ({@code USE}) tax quote. No code here names a country: the country is {@link TaxCountry}, the place
 * {@link PurchasePlace}, and the rules are pos-tax configuration (AW48).
 *
 * <ul>
 *   <li>A decision (approve, {@code ACCEPT}, automatic approval) reads the rules through {@link #rules}, never cached,
 *       and only when the bill could qualify. The bill read uses {@link #cachedRules}, cached per (country, date) for
 *       {@code accounting.tax.purchase-rules.cache-ttl} (default {@code PT5M}); a failure is never cached.
 *   <li>The quote ({@link #quote}) is asked once per decision: {@code committable = false}, the ledger currency
 *       (ADR-0067 PC-11 (a)), the posting date, the bill as reference, one line per untaxed expense line at its net.
 *   <li>pos-tax answering anything but an answer is 503 {@code SERVICE_UNAVAILABLE} ({@link
 *       TaxServiceUnavailableException}), never read as "off" (AW49).
 * </ul>
 */
@Component
public class VendorBillPurchaseTax {

    /** The request field of the per-bill override (approve and {@code ACCEPT}). */
    public static final String OVERRIDE_FIELD = "taxOnResaleOverrideJustification";

    private static final String USE = "USE";
    private static final int SCALE = 2;

    private final TaxReferenceClient client;
    private final TaxCountry taxCountry;
    private final PurchasePlace purchasePlace;
    private final LedgerCurrency ledgerCurrency;
    private final SupplierVendorCopies vendorCopies;
    private final VendorBillLineRepository billLines;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(TaxPurchaseRules rules, Instant expires) {}

    public VendorBillPurchaseTax(
            TaxReferenceClient client,
            TaxCountry taxCountry,
            PurchasePlace purchasePlace,
            LedgerCurrency ledgerCurrency,
            SupplierVendorCopies vendorCopies,
            VendorBillLineRepository billLines,
            Clock clock,
            @Value("${accounting.tax.purchase-rules.cache-ttl:PT5M}") Duration cacheTtl) {
        this.client = client;
        this.taxCountry = taxCountry;
        this.purchasePlace = purchasePlace;
        this.ledgerCurrency = ledgerCurrency;
        this.vendorCopies = vendorCopies;
        this.billLines = billLines;
        this.clock = clock;
        this.cacheTtl = cacheTtl;
    }

    /**
     * What {@code bill}, posted with {@code classification}, offers the purchase-tax rules, from its stored lines
     * ({@link VendorBillPostingService#purchaseTaxBasis(VendorBill, List, VendorBillPostingService.Classification)}).
     */
    public VendorBillPostingService.@NonNull PurchaseTaxBasis basis(
            @NonNull VendorBill bill, VendorBillPostingService.@Nullable Classification classification) {
        return VendorBillPostingService.purchaseTaxBasis(
                bill,
                billLines.findByVendorBill_VendorBillIdOrderByLineNumber(bill.getVendorBillId()),
                classification == null ? new VendorBillPostingService.Classification(null, null) : classification);
    }

    /**
     * The tax country's purchase-tax rules on {@code asOf}, for a decision: always asked of pos-tax.
     *
     * @throws TaxServiceUnavailableException 503 when pos-tax gives no answer
     */
    public @NonNull TaxPurchaseRules rules(@NonNull LocalDate asOf) {
        return client.purchaseRules(taxCountry.code(), asOf);
    }

    /**
     * The tax country's purchase-tax rules on {@code asOf}, for a read: from the cache while fresh, else asked of
     * pos-tax and cached. Empty when pos-tax gives no answer (the read then reports the rules unavailable).
     */
    public @NonNull Optional<TaxPurchaseRules> cachedRules(@NonNull LocalDate asOf) {
        String key = taxCountry.code() + "|" + asOf;
        Instant now = Instant.now(clock);
        Cached cached = cache.get(key);
        if (cached != null && now.isBefore(cached.expires())) {
            return Optional.of(cached.rules());
        }
        try {
            TaxPurchaseRules rules = client.purchaseRules(taxCountry.code(), asOf);
            cache.put(key, new Cached(rules, now.plus(cacheTtl)));
            return Optional.of(rules);
        } catch (TaxServiceUnavailableException unavailable) {
            return Optional.empty();
        }
    }

    /** Whether the bill's vendor accepts tax charged on goods for resale (its AP setting). */
    public boolean vendorAccepts(@NonNull VendorBill bill) {
        return vendorCopies.acceptsTaxOnResaleGoods(bill.getVendorId());
    }

    /**
     * The self-assessed tax of {@code basis}'s untaxed expense lines, quoted once for this decision: the sum of the tax
     * pos-tax returned for each line, the lines with none left out.
     *
     * @param postingDate the entry's posting date (AW42), sent as the transaction date
     * @return the accrual, or null when pos-tax returned no tax above 0.00
     * @throws TaxServiceUnavailableException 503 when pos-tax gives no answer, or an answer without line taxes
     */
    public VendorBillPostingService.@Nullable UseTax quote(
            @NonNull VendorBill bill,
            VendorBillPostingService.@NonNull PurchaseTaxBasis basis,
            @NonNull LocalDate postingDate) {
        String key = basis.expenseMappingKey();
        if (!basis.mayAccrue() || key == null) {
            return null;
        }
        List<TaxUseQuote.Line> lines = basis.untaxedExpense().stream()
                .map(line -> new TaxUseQuote.Line(
                        line.lineItemId(),
                        "Bill " + bill.getBillNumber() + " line " + line.lineItemId(),
                        BigDecimal.ONE,
                        line.net()))
                .toList();
        TaxUseQuote.Response answer = client.useTax(new TaxUseQuote.Request(
                lines,
                new TaxUseQuote.Address(taxCountry.code(), purchasePlace.regionCode(), purchasePlace.postalCode()),
                ledgerCurrency.code(),
                USE,
                postingDate.toString(),
                bill.getVendorBillId(),
                false));
        if (answer.lineItemTaxes() == null) {
            throw new TaxServiceUnavailableException("The tax service returned no line taxes");
        }
        Set<String> asked = new HashSet<>();
        basis.untaxedExpense().forEach(line -> asked.add(line.lineItemId()));
        BigDecimal total = BigDecimal.ZERO.setScale(SCALE);
        for (TaxUseQuote.LineTax line : answer.lineItemTaxes()) {
            if (line != null
                    && line.lineItemId() != null
                    && asked.contains(line.lineItemId())
                    && line.taxAmount() != null
                    && line.taxAmount().signum() > 0) {
                total = total.add(line.taxAmount().setScale(SCALE, RoundingMode.HALF_UP));
            }
        }
        return total.signum() > 0 ? new VendorBillPostingService.UseTax(key, total) : null;
    }
}
