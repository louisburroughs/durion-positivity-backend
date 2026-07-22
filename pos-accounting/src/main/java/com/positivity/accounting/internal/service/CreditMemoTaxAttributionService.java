package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.CreditMemoTax;
import com.positivity.accounting.internal.entity.ExtInvoiceTax;
import com.positivity.accounting.internal.repository.CreditMemoTaxRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceTaxRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Freezes a credit memo's reversed tax as a per-jurisdiction breakdown at creation time
 * (issue #996).
 *
 * <p><b>Why this exists.</b> {@code ext_invoice_tax} is invoice-side only, so before this
 * the T8 sales-tax liability report had to approximate a credit's jurisdictional split at
 * read time — pro-rating the scalar {@code taxAmountReversed} across the original
 * invoice's collected tax on every run. Two things that approximation cannot do:
 *
 * <ol>
 *   <li>survive a change to the invoice's replicated breakdown after the credit posted —
 *       the credit's attribution is an accounting fact and must be frozen with it; and</li>
 *   <li>guarantee that a <em>sequence</em> of partial credits reverses exactly each
 *       jurisdiction's collected tax (independent per-run rounding drifts by cents).</li>
 * </ol>
 *
 * <p><b>How the split is derived.</b> The weights are the original invoice's
 * per-jurisdiction collected tax ({@code ext_invoice_tax.tax_amount}).
 *
 * <ul>
 *   <li><b>Partial credit</b> — {@code taxReversed} is allocated across those weights by
 *       {@link TaxCreditAllocator}, which rounds at currency scale and pushes the residual
 *       onto the largest-weight jurisdiction so the parts sum exactly to the scalar.</li>
 *   <li><b>Final credit</b> (this credit consumes the invoice's remaining net amount, the
 *       same condition that makes {@code CreditMemoServiceImpl} reverse the residual tax) —
 *       each jurisdiction reverses {@code collected − alreadyReversedThere}, so cumulative
 *       reversals land exactly on each jurisdiction's collected tax rather than merely
 *       summing to the right total. Any leftover from clamping a negative residual is
 *       pushed onto the largest-weight jurisdiction, preserving the exact-sum guarantee.</li>
 * </ul>
 *
 * <p>When the invoice has no replicated breakdown — a pre-T5 invoice, or one whose
 * breakdown was never emitted — no rows are written: there is no jurisdiction to attribute
 * to, and inventing one would net a credit against a jurisdiction that collected nothing.
 * The report surfaces that amount explicitly as unattributed credits rather than silently
 * as drift.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreditMemoTaxAttributionService {

    /** Currency scale (dollars and cents), matching {@link TaxCreditAllocator}. */
    private static final int CURRENCY_SCALE = 2;

    private final ExtInvoiceTaxRepository extInvoiceTaxRepository;
    private final CreditMemoTaxRepository creditMemoTaxRepository;

    /**
     * Persist the per-jurisdiction attribution of one credit memo's reversed tax.
     *
     * <p>Must run inside the credit memo's own transaction so the attribution exists iff
     * the credit memo does.
     *
     * @param creditMemoId      the credit memo being attributed
     * @param originalInvoiceId the invoice whose frozen breakdown supplies the weights
     * @param taxReversed       the credit's scalar reversed tax (non-negative)
     * @param finalCredit       true when this credit consumes the invoice's remaining net
     *                          amount, so each jurisdiction reverses its own residual
     * @return the persisted rows, summing exactly to {@code taxReversed}; empty when
     *         nothing could be attributed (no tax reversed, or no invoice breakdown)
     */
    @NonNull
    public List<CreditMemoTax> attribute(
            @NonNull UUID creditMemoId,
            @NonNull UUID originalInvoiceId,
            @NonNull BigDecimal taxReversed,
            boolean finalCredit) {

        BigDecimal target = taxReversed.setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
        if (target.signum() <= 0) {
            return List.of();
        }

        Map<JurisdictionKey, BigDecimal> weights = collectedTaxByJurisdiction(originalInvoiceId);
        if (weights.isEmpty()) {
            log.warn(
                    "Credit memo {} reverses tax {} but invoice {} has no ext_invoice_tax rows with collected tax; "
                            + "reversal left unattributed (surfaces as unattributed credits in the T8 report)",
                    creditMemoId,
                    target,
                    originalInvoiceId);
            return List.of();
        }

        Map<JurisdictionKey, BigDecimal> split = finalCredit
                ? residualSplit(originalInvoiceId, weights, target)
                : TaxCreditAllocator.allocate(target, weights);

        List<CreditMemoTax> rows = new ArrayList<>();
        for (Map.Entry<JurisdictionKey, BigDecimal> entry : split.entrySet()) {
            if (entry.getValue().signum() == 0) {
                continue; // nothing reversed in this jurisdiction — do not persist a zero row
            }
            rows.add(CreditMemoTax.builder()
                    .creditMemoId(creditMemoId)
                    .jurisdictionType(entry.getKey().type())
                    .jurisdictionCode(entry.getKey().code())
                    .taxAmountReversed(entry.getValue())
                    .build());
        }
        return creditMemoTaxRepository.saveAll(rows);
    }

    /**
     * The original invoice's collected tax per jurisdiction, keyed in
     * {@link JurisdictionKey} order so the allocation is independent of row order. Exempt
     * rows contribute no collected tax and are naturally excluded (their {@code tax_amount}
     * is zero); jurisdictions that end up with zero total weight are dropped, since a
     * reversal cannot be attributed to a jurisdiction that collected nothing.
     */
    private Map<JurisdictionKey, BigDecimal> collectedTaxByJurisdiction(UUID originalInvoiceId) {
        List<ExtInvoiceTax> breakdown = extInvoiceTaxRepository.findByInvoiceId(originalInvoiceId);
        Map<JurisdictionKey, BigDecimal> weights = new TreeMap<>();
        if (breakdown == null) {
            return weights;
        }
        for (ExtInvoiceTax row : breakdown) {
            BigDecimal collected = row.getTaxAmount() == null ? BigDecimal.ZERO : row.getTaxAmount();
            weights.merge(
                    new JurisdictionKey(row.getJurisdictionType(), row.getJurisdictionCode()),
                    collected,
                    BigDecimal::add);
        }
        weights.values().removeIf(weight -> weight.signum() <= 0);
        return weights;
    }

    /**
     * Final-credit split: each jurisdiction reverses what it has left
     * ({@code collected − alreadyReversed}), clamped at zero, with any leftover distributed
     * across the jurisdictions that still have headroom.
     *
     * <p>In the ordinary case the clamped parts already sum to {@code target}, because the caller
     * only flags {@code finalCredit} when the credit reverses the invoice's residual tax.
     *
     * <p><b>The mixed pre-/post-#996 case.</b> {@code alreadyReversed} can only see
     * {@code credit_memo_tax} rows, but the scalar residual the caller computed sums
     * {@code credit_memo.tax_amount_reversed} across <em>all</em> POSTED memos. For an invoice
     * partially credited before this table existed (or whose {@code ext_invoice_tax} rows arrived
     * after its first credit memo), the per-jurisdiction view reads zero already-reversed while the
     * scalar does not — so {@code running} exceeds {@code target} and the correction is negative.
     * Distributing that shortfall proportionally across jurisdictions with headroom keeps every
     * part non-negative and never reverses more than a jurisdiction actually collected, which the
     * old "dump it all on the largest weight" correction could not promise.
     */
    private Map<JurisdictionKey, BigDecimal> residualSplit(
            UUID originalInvoiceId, Map<JurisdictionKey, BigDecimal> weights, BigDecimal target) {

        Map<JurisdictionKey, BigDecimal> headroom = new LinkedHashMap<>();
        BigDecimal running = BigDecimal.ZERO;
        for (Map.Entry<JurisdictionKey, BigDecimal> entry : weights.entrySet()) {
            JurisdictionKey key = entry.getKey();
            BigDecimal alreadyReversed = nullSafe(creditMemoTaxRepository.sumReversedForInvoiceJurisdiction(
                    originalInvoiceId, key.type(), key.code()));
            BigDecimal remaining =
                    entry.getValue().subtract(alreadyReversed).setScale(CURRENCY_SCALE, RoundingMode.DOWN);
            BigDecimal clamped = remaining.signum() > 0 ? remaining : BigDecimal.ZERO.setScale(CURRENCY_SCALE);
            headroom.put(key, clamped);
            running = running.add(clamped);
        }

        if (running.compareTo(target) == 0) {
            return headroom;
        }

        if (running.compareTo(target) > 0) {
            // Less to reverse than the jurisdictions have headroom for: allocate the target across
            // the headroom itself, so no jurisdiction is pushed past its collected tax.
            log.warn(
                    "Final-credit residual for invoice {} exceeds the scalar reversal "
                            + "(perJurisdictionHeadroom={}, target={}); allocating across headroom. This normally "
                            + "means the invoice was partially credited before credit_memo_tax existed",
                    running,
                    target,
                    originalInvoiceId);
            return TaxCreditAllocator.allocate(target, headroom);
        }

        // More to reverse than the frozen breakdown accounts for — the invoice's replicated tax is
        // smaller than the scalar it was credited against. Put the excess where tax was actually
        // collected rather than inventing a jurisdiction.
        BigDecimal excess = target.subtract(running);
        log.warn(
                "Final-credit residual for invoice {} falls short of the scalar reversal by {} "
                        + "(perJurisdictionHeadroom={}, target={}); distributing the excess pro-rata across "
                        + "collected tax",
                originalInvoiceId,
                excess,
                running,
                target);
        Map<JurisdictionKey, BigDecimal> split = new LinkedHashMap<>(headroom);
        for (Map.Entry<JurisdictionKey, BigDecimal> extra :
                TaxCreditAllocator.allocate(excess, weights).entrySet()) {
            split.merge(extra.getKey(), extra.getValue(), BigDecimal::add);
        }
        return split;
    }

    private static BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
