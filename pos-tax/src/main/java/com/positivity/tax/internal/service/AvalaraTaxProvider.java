package com.positivity.tax.internal.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxCalculationResponse.JurisdictionTax;
import com.positivity.tax.common.dto.TaxCalculationResponse.LineItemTax;
import com.positivity.tax.common.dto.TaxJurisdiction;
import com.positivity.tax.common.dto.TaxLineItem;
import com.positivity.tax.common.dto.TaxProviderTransactionResult;
import com.positivity.tax.common.enums.TaxCalculationType;
import com.positivity.tax.common.enums.TaxJurisdictionType;
import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.exception.TaxCalculationException;
import com.positivity.tax.service.TaxProviderClient;
import io.github.resilience4j.retry.Retry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Concrete Avalara AvaTax REST v2 adapter (story T6-external, decision R-T1).
 *
 * <h2>AvaTax method mapping</h2>
 * <ul>
 *   <li><b>estimate</b> (read-only, {@code committable=false}) &rarr;
 *       {@code POST /api/v2/transactions/create} with {@code type=SalesOrder, commit=false} —
 *       an uncommitted, non-persisted estimate.</li>
 *   <li><b>estimate</b> ({@code committable=true}, #985) &rarr;
 *       {@code POST /api/v2/transactions/createoradjust} with
 *       {@code type=SalesInvoice, commit=false, code=referenceId} — a PERSISTED, uncommitted
 *       document that the later {@code commit(referenceId)} promotes (Option A of #985).
 *       {@code createoradjust} (not {@code create}) so a retried finalization adjusts the
 *       existing document instead of failing on a duplicate code.</li>
 *   <li><b>refund</b> &rarr; {@code POST /api/v2/transactions/create} with
 *       {@code type=ReturnInvoice}, {@code referenceCode=originalReferenceId}, positive
 *       amounts (platform convention: callers negate at posting, story T4).</li>
 *   <li><b>commit</b> &rarr;
 *       {@code POST /api/v2/companies/{companyCode}/transactions/{referenceId}/commit}
 *       (documentType {@code SalesInvoice}, body {@code {"commit":true}}); the source
 *       {@code referenceId} is the AvaTax document {@code code}, so retries never
 *       double-commit (idempotency).</li>
 *   <li><b>voidTransaction</b> &rarr;
 *       {@code POST /api/v2/companies/{companyCode}/transactions/{referenceId}/void}
 *       (body {@code {"code":"DocVoided"}}).</li>
 * </ul>
 * Endpoints/models per Avalara Developer docs
 * (<a href="https://developer.avalara.com/api-reference/avatax/rest/v2/methods/Transactions/">
 * Transactions API</a>) and the AvaTax REST V2 SDK.
 *
 * <h2>Totals reconciliation</h2>
 * AvaTax per-line and per-jurisdiction tax figures are validated/repaired against the
 * {@code Sum(lineTax) == totalTax == Sum(jurisdictionTax)} invariant with the Wave-1
 * {@link TaxTotalsReconciler} (same deterministic cent-drift rule, story T1).
 *
 * <h2>Error handling (ADR-0021)</h2>
 * Transport/HTTP failures are wrapped in {@link TaxCalculationException}; the shared
 * {@code TaxRetryPredicate} on the injected {@link Retry} retries only {@code 5xx}/timeouts
 * and never {@code 4xx} (a 400 address/validation error is deterministic and surfaces
 * immediately).
 *
 * <h2>D-T1 note (not built)</h2>
 * Avalara CertCapture (ECM) could later become the system of record for the T3
 * exemption-certificate registry. For now the in-platform registry stays authoritative and
 * this story ships no CertCapture integration.
 */
@Slf4j
@Component
public class AvalaraTaxProvider implements TaxProviderClient {

    /** Stable provider label recorded on the transaction log. */
    static final String PROVIDER_NAME = "AVALARA";

    private static final int MONEY_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String CREATE_URI = "/api/v2/transactions/create";
    private static final String CREATE_OR_ADJUST_URI = "/api/v2/transactions/createoradjust";
    private static final String COMMIT_URI = "/api/v2/companies/{companyCode}/transactions/{code}/commit";
    private static final String VOID_URI = "/api/v2/companies/{companyCode}/transactions/{code}/void";
    private static final String SHIP_TO = "ShipTo";

    private final RestClient restClient;
    private final Retry retry;
    private final TaxProperties properties;
    private final Clock clock;
    private final TaxTotalsReconciler reconciler = new TaxTotalsReconciler();

    public AvalaraTaxProvider(
            RestClient avalaraRestClient, Retry taxServiceRetry, TaxProperties properties, Clock clock) {
        this.restClient = avalaraRestClient;
        this.retry = taxServiceRetry;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @NonNull
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    @NonNull
    public TaxCalculationResponse estimate(@NonNull TaxCalculationRequest request) {
        // #985 Option A: a committable calculation must leave a PERSISTED provider document
        // behind under code == referenceId, or the later commit(referenceId) resolves nothing
        // (AvaTax never persists a SalesOrder). SalesInvoice + commit=false is that persisted,
        // still-uncommitted document; TaxProviderLifecycleService.commit() promotes it.
        if (request.isCommittable()) {
            CreateTransactionModel model = buildCreateModel(request, "SalesInvoice", null);
            // createoradjust: re-running finalization for the same invoice adjusts the existing
            // document rather than erroring on a duplicate code — keeps the call idempotent.
            TransactionModel tx = call(() -> restClient
                    .post()
                    .uri(CREATE_OR_ADJUST_URI)
                    .body(new CreateOrAdjustTransactionModel(model))
                    .retrieve()
                    .body(TransactionModel.class));
            return mapResponse(request, tx, TaxCalculationType.SALE);
        }
        CreateTransactionModel model = buildCreateModel(request, "SalesOrder", null);
        TransactionModel tx = call(
                () -> restClient.post().uri(CREATE_URI).body(model).retrieve().body(TransactionModel.class));
        return mapResponse(request, tx, TaxCalculationType.SALE);
    }

    @Override
    @NonNull
    public TaxCalculationResponse refund(@NonNull TaxCalculationRequest request, @NonNull UUID originalReferenceId) {
        // ReturnInvoice referencing the original sale; amounts stay positive (story T4 —
        // callers negate at posting). AvaTax reprices at the supplied original sale date.
        CreateTransactionModel model = buildCreateModel(request, "ReturnInvoice", originalReferenceId.toString());
        TransactionModel tx = call(
                () -> restClient.post().uri(CREATE_URI).body(model).retrieve().body(TransactionModel.class));
        return mapResponse(request, tx, TaxCalculationType.REFUND);
    }

    @Override
    @NonNull
    public TaxProviderTransactionResult commit(@NonNull UUID referenceId) {
        String companyCode = properties.getAvalara().getCompanyCode();
        TransactionModel tx = call(() -> restClient
                .post()
                .uri(uriBuilder -> uriBuilder
                        .path(COMMIT_URI)
                        .queryParam("documentType", "SalesInvoice")
                        .build(companyCode, referenceId.toString()))
                .body(new CommitTransactionModel(true))
                .retrieve()
                .body(TransactionModel.class));
        return new TaxProviderTransactionResult(
                referenceId, TaxProviderTransactionStatus.COMMITTED, externalId(tx), "committed at AvaTax");
    }

    @Override
    @NonNull
    public TaxProviderTransactionResult voidTransaction(@NonNull UUID referenceId) {
        String companyCode = properties.getAvalara().getCompanyCode();
        TransactionModel tx = call(() -> restClient
                .post()
                .uri(uriBuilder -> uriBuilder.path(VOID_URI).build(companyCode, referenceId.toString()))
                .body(new VoidTransactionModel("DocVoided"))
                .retrieve()
                .body(TransactionModel.class));
        return new TaxProviderTransactionResult(
                referenceId, TaxProviderTransactionStatus.VOIDED, externalId(tx), "voided at AvaTax");
    }

    // ---- request mapping -------------------------------------------------------------

    @NonNull
    private CreateTransactionModel buildCreateModel(
            @NonNull TaxCalculationRequest request, @NonNull String type, @Nullable String referenceCode) {
        requireReferenceIdWhenCommittable(request);
        TaxProperties.Avalara avalara = properties.getAvalara();
        AvalaraAddress shipTo = buildShipToAddress(request.getDestinationAddress());
        List<AvalaraLine> lines = buildLines(request);

        return new CreateTransactionModel(
                type,
                avalara.getCompanyCode(),
                transactionDate(request),
                customerCode(request),
                referenceIdCode(request),
                request.getCurrencyCode(),
                false,
                referenceCode,
                Map.of(SHIP_TO, shipTo),
                lines);
    }

    /**
     * #984: a committable calculation becomes a provider document that a later commit/void must
     * resolve by code (== referenceId). A null id would let AvaTax auto-assign a code the caller
     * can never reproduce, so reject it here rather than 404 at finalization. Read-only estimates
     * (committable=false) may omit referenceId — AvaTax does not persist them.
     */
    private static void requireReferenceIdWhenCommittable(@NonNull TaxCalculationRequest request) {
        if (request.isCommittable() && request.getReferenceId() == null) {
            throw new TaxCalculationException(
                    "referenceId is required for a committable tax calculation (it is the provider document code)");
        }
    }

    @NonNull
    private static AvalaraAddress buildShipToAddress(TaxCalculationRequest.@Nullable TaxAddress dest) {
        return new AvalaraAddress(
                dest == null ? null : dest.getLine1(),
                dest == null ? null : dest.getLine2(),
                dest == null ? null : dest.getCity(),
                dest == null ? null : dest.getRegionCode(),
                dest == null ? null : dest.getCountryCode(),
                dest == null ? null : dest.getPostalCode());
    }

    @NonNull
    private static List<AvalaraLine> buildLines(@NonNull TaxCalculationRequest request) {
        List<AvalaraLine> lines = new ArrayList<>();
        for (TaxLineItem item : request.getLineItems()) {
            lines.add(new AvalaraLine(
                    item.getLineItemId(),
                    item.getQuantity(),
                    item.getSubtotal(),
                    item.getTaxCategory(),
                    item.getDescription(),
                    exemptionEntityUseCode(item)));
        }
        return lines;
    }

    /** AvaTax {@code entityUseCode}: the line's exemption reason when it is claimed exempt (story T3). */
    @Nullable
    private static String exemptionEntityUseCode(@NonNull TaxLineItem item) {
        return item.isTaxExempt()
                ? item.getExemptionReasonCode() != null
                        ? item.getExemptionReasonCode().name()
                        : null
                : null;
    }

    @NonNull
    private static String customerCode(@NonNull TaxCalculationRequest request) {
        return request.getCustomerId() != null ? request.getCustomerId() : "GUEST";
    }

    @Nullable
    private static String referenceIdCode(@NonNull TaxCalculationRequest request) {
        return request.getReferenceId() != null ? request.getReferenceId().toString() : null;
    }

    @NonNull
    private String transactionDate(@NonNull TaxCalculationRequest request) {
        String raw = request.getTransactionDate();
        if (raw == null || raw.isBlank()) {
            return LocalDate.now(clock).toString();
        }
        try {
            return Instant.parse(raw).atZone(ZoneOffset.UTC).toLocalDate().toString();
        } catch (DateTimeParseException _) {
            try {
                return LocalDate.parse(raw).toString();
            } catch (DateTimeParseException _) {
                // Match the test-mode calculator: a non-blank but unparseable date is a client
                // error, not a silent fall-back to today (which would hide the bug and make
                // effective-dated rates depend on the provider).
                throw new IllegalArgumentException(
                        "transactionDate must be a valid ISO-8601 date or date-time: " + raw);
            }
        }
    }

    // ---- response mapping ------------------------------------------------------------

    @NonNull
    private TaxCalculationResponse mapResponse(
            @NonNull TaxCalculationRequest request,
            @Nullable TransactionModel tx,
            @NonNull TaxCalculationType calculationType) {
        if (tx == null) {
            throw new TaxCalculationException("AvaTax returned an empty transaction response");
        }

        BigDecimal subtotal = round(sumLineSubtotals(request));
        BigDecimal totalTax = round(tx.totalTax() != null ? tx.totalTax() : BigDecimal.ZERO);

        Map<String, TaxLineItem> requestLines = new LinkedHashMap<>();
        Map<String, BigDecimal> requestSubtotals = new LinkedHashMap<>();
        for (TaxLineItem item : request.getLineItems()) {
            requestSubtotals.put(item.getLineItemId(), round(item.getSubtotal()));
            requestLines.put(item.getLineItemId(), item);
        }

        Map<String, JurisdictionAccumulator> jurisdictionByCode = new LinkedHashMap<>();
        List<BigDecimal> rawLineTaxes = new ArrayList<>();
        List<LineItemTax> lineItemTaxes =
                mapLineItemTaxes(tx, requestSubtotals, requestLines, jurisdictionByCode, rawLineTaxes);

        reconcileLineTaxesToTotal(lineItemTaxes, rawLineTaxes, totalTax);
        List<TaxJurisdiction> jurisdictions = aggregateJurisdictions(request, jurisdictionByCode, totalTax);

        return TaxCalculationResponse.builder()
                .subtotal(subtotal)
                .totalTax(totalTax)
                .total(subtotal.add(totalTax))
                .effectiveTaxRate(effectiveRate(request, tx, totalTax))
                .jurisdictions(jurisdictions)
                .lineItemTaxes(lineItemTaxes)
                .testMode(false)
                .calculatedAt(clock.instant())
                .referenceId(request.getReferenceId())
                .referenceType(request.getReferenceType())
                .externalTransactionId(externalId(tx))
                .calculationType(calculationType)
                .build();
    }

    /** Per-line tax breakdown from AvaTax {@code lines[].details[]}, accumulating jurisdictions as it goes. */
    @NonNull
    private List<LineItemTax> mapLineItemTaxes(
            @NonNull TransactionModel tx,
            @NonNull Map<String, BigDecimal> requestSubtotals,
            @NonNull Map<String, TaxLineItem> requestLines,
            @NonNull Map<String, JurisdictionAccumulator> jurisdictionByCode,
            @NonNull List<BigDecimal> rawLineTaxes) {
        List<LineItemTax> lineItemTaxes = new ArrayList<>();
        List<TransactionLineModel> avaLines = tx.lines() != null ? tx.lines() : List.of();
        for (TransactionLineModel avaLine : avaLines) {
            BigDecimal lineTax = avaLine.tax() != null ? avaLine.tax() : BigDecimal.ZERO;
            rawLineTaxes.add(lineTax);

            List<JurisdictionTax> lineJurisdictions = mapLineJurisdictions(avaLine, jurisdictionByCode);

            String lineId = avaLine.lineNumber();
            BigDecimal lineSubtotal = requestSubtotals.getOrDefault(lineId, round(safeAmount(avaLine.taxableAmount())));
            TaxLineItem requestLine = lineId != null ? requestLines.get(lineId) : null;
            boolean lineExempt = requestLine != null && requestLine.isTaxExempt();
            lineItemTaxes.add(LineItemTax.builder()
                    .lineItemId(lineId != null ? lineId : "")
                    .subtotal(lineSubtotal)
                    .taxAmount(round(lineTax))
                    .total(lineSubtotal.add(round(lineTax)))
                    .taxExempt(lineExempt)
                    .jurisdictions(lineJurisdictions)
                    .build());
        }
        return lineItemTaxes;
    }

    @NonNull
    private List<JurisdictionTax> mapLineJurisdictions(
            @NonNull TransactionLineModel avaLine, @NonNull Map<String, JurisdictionAccumulator> jurisdictionByCode) {
        List<JurisdictionTax> lineJurisdictions = new ArrayList<>();
        List<TransactionLineDetailModel> details = avaLine.details() != null ? avaLine.details() : List.of();
        for (TransactionLineDetailModel detail : details) {
            BigDecimal rate = detail.rate() != null ? detail.rate() : BigDecimal.ZERO;
            BigDecimal amount = detail.tax() != null ? detail.tax() : BigDecimal.ZERO;
            TaxJurisdictionType type = mapJurisdictionType(detail.jurisdictionType());
            String code = detail.jurisCode() != null ? detail.jurisCode() : type.code();
            lineJurisdictions.add(JurisdictionTax.builder()
                    .jurisdictionType(type)
                    .code(code)
                    .rate(rate)
                    .amount(round(amount))
                    .exempt(false)
                    .build());
            jurisdictionByCode
                    .computeIfAbsent(type.code() + "|" + code, k -> new JurisdictionAccumulator(type, rate))
                    .add(amount);
        }
        return lineJurisdictions;
    }

    /** Sigma-invariant repair: reconcile line tax amounts to the AvaTax grand total. */
    private void reconcileLineTaxesToTotal(
            @NonNull List<LineItemTax> lineItemTaxes,
            @NonNull List<BigDecimal> rawLineTaxes,
            @NonNull BigDecimal totalTax) {
        if (lineItemTaxes.isEmpty()) {
            return;
        }
        List<BigDecimal> reconciledLineTaxes = reconciler.reconcileAmountsToTarget(rawLineTaxes, totalTax);
        for (int i = 0; i < lineItemTaxes.size(); i++) {
            LineItemTax lit = lineItemTaxes.get(i);
            lit.setTaxAmount(reconciledLineTaxes.get(i));
            lit.setTotal(lit.getSubtotal().add(reconciledLineTaxes.get(i)));
        }
    }

    /** Aggregates top-level jurisdictions and reconciles their amounts to the grand total. */
    @NonNull
    private List<TaxJurisdiction> aggregateJurisdictions(
            @NonNull TaxCalculationRequest request,
            @NonNull Map<String, JurisdictionAccumulator> jurisdictionByCode,
            @NonNull BigDecimal totalTax) {
        List<BigDecimal> jurisdictionAmounts = new ArrayList<>();
        for (JurisdictionAccumulator acc : jurisdictionByCode.values()) {
            jurisdictionAmounts.add(acc.amount);
        }
        List<BigDecimal> reconciled = jurisdictionAmounts.isEmpty()
                ? jurisdictionAmounts
                : reconciler.reconcileAmountsToTarget(jurisdictionAmounts, totalTax);

        List<TaxJurisdiction> jurisdictions = new ArrayList<>();
        int j = 0;
        for (JurisdictionAccumulator acc : jurisdictionByCode.values()) {
            jurisdictions.add(TaxJurisdiction.builder()
                    .countryCode(request.getCountryCode())
                    .regionCode(request.getStateCode())
                    .city(request.getCity())
                    .postalCode(request.getPostalCode())
                    // Top-level taxRate is expressed as percentage points (e.g. 7.25).
                    .taxRate(acc.rate.multiply(HUNDRED))
                    .jurisdictionType(acc.type)
                    .taxAmount(reconciled.get(j++))
                    .build());
        }
        return jurisdictions;
    }

    /**
     * Effective rate is tax over the TAXABLE base, not gross subtotal.
     *
     * <p>Exempt lines carry subtotal but contribute no tax, so including them would dilute the rate
     * (#984). Prefer AvaTax's own taxable figure — it already nets out exempt/entity-use lines — and
     * fall back to the sum of non-exempt request subtotals when the provider omits it.
     */
    @NonNull
    private BigDecimal effectiveRate(
            @NonNull TaxCalculationRequest request, @NonNull TransactionModel tx, @NonNull BigDecimal totalTax) {
        BigDecimal taxableBase = tx.totalTaxable() != null && tx.totalTaxable().signum() != 0
                ? round(tx.totalTaxable())
                : round(sumNonExemptSubtotals(request));
        return taxableBase.signum() == 0
                ? BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                : totalTax.multiply(HUNDRED).divide(taxableBase, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    @NonNull
    private BigDecimal sumLineSubtotals(@NonNull TaxCalculationRequest request) {
        BigDecimal sum = BigDecimal.ZERO;
        for (TaxLineItem item : request.getLineItems()) {
            sum = sum.add(item.getSubtotal());
        }
        return sum;
    }

    /** Taxable base for the effective-rate denominator: request subtotal excluding exempt lines (#984). */
    @NonNull
    private BigDecimal sumNonExemptSubtotals(@NonNull TaxCalculationRequest request) {
        BigDecimal sum = BigDecimal.ZERO;
        for (TaxLineItem item : request.getLineItems()) {
            if (!item.isTaxExempt()) {
                sum = sum.add(item.getSubtotal());
            }
        }
        return sum;
    }

    @NonNull
    private static TaxJurisdictionType mapJurisdictionType(@Nullable String avalaraType) {
        if (avalaraType == null) {
            return TaxJurisdictionType.SPECIAL;
        }
        return switch (avalaraType.trim().toUpperCase()) {
            case "COUNTRY" -> TaxJurisdictionType.COUNTRY;
            case "STATE" -> TaxJurisdictionType.STATE;
            case "COUNTY" -> TaxJurisdictionType.COUNTY;
            case "CITY" -> TaxJurisdictionType.CITY;
            default -> TaxJurisdictionType.SPECIAL;
        };
    }

    @Nullable
    private static String externalId(@Nullable TransactionModel tx) {
        if (tx == null) {
            return null;
        }
        if (tx.id() != null) {
            return String.valueOf(tx.id());
        }
        return tx.code();
    }

    @NonNull
    private static BigDecimal safeAmount(@Nullable BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    @NonNull
    private static BigDecimal round(@NonNull BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Decorate the AvaTax HTTP call with the shared retry and wrap transport/HTTP failures in
     * {@link TaxCalculationException}. The retry predicate walks the cause chain and retries
     * only {@code 5xx}/timeouts (ADR-0021); {@code 4xx} surfaces deterministically.
     */
    @NonNull
    private <T> T call(@NonNull Supplier<T> httpCall) {
        Supplier<T> supplier = () -> {
            try {
                T result = httpCall.get();
                if (result == null) {
                    throw new TaxCalculationException("AvaTax returned a null response");
                }
                return result;
            } catch (TaxCalculationException ex) {
                throw ex;
            } catch (Exception ex) {
                // Keep the client-facing message stable; log the provider detail rather than
                // leaking it into the exception message.
                log.warn("AvaTax call failed: {}", ex.getMessage(), ex);
                throw new TaxCalculationException("AvaTax call failed", ex);
            }
        };
        return Retry.decorateSupplier(retry, supplier).get();
    }

    /** Running per-jurisdiction accumulator keyed by type+code. */
    private static final class JurisdictionAccumulator {
        private final TaxJurisdictionType type;
        private final BigDecimal rate;
        private BigDecimal amount = BigDecimal.ZERO;

        private JurisdictionAccumulator(TaxJurisdictionType type, BigDecimal rate) {
            this.type = type;
            this.rate = rate;
        }

        private void add(BigDecimal delta) {
            amount = amount.add(delta != null ? delta : BigDecimal.ZERO);
        }
    }

    // ---- AvaTax REST v2 wire models --------------------------------------------------

    /** AvaTax {@code CreateTransactionModel} (subset used by this adapter). */
    record CreateTransactionModel(
            String type,
            String companyCode,
            String date,
            String customerCode,
            String code,
            String currencyCode,
            boolean commit,
            String referenceCode,
            Map<String, AvalaraAddress> addresses,
            List<AvalaraLine> lines) {}

    /** AvaTax {@code AddressLocationInfo}. */
    record AvalaraAddress(String line1, String line2, String city, String region, String country, String postalCode) {}

    /** AvaTax {@code LineItemModel} (subset). */
    record AvalaraLine(
            String number,
            BigDecimal quantity,
            BigDecimal amount,
            String taxCode,
            String description,
            String entityUseCode) {}

    /** AvaTax {@code CreateOrAdjustTransactionModel} wrapper (#985 committable path). */
    record CreateOrAdjustTransactionModel(CreateTransactionModel createTransactionModel) {}

    /** AvaTax {@code CommitTransactionModel}. */
    record CommitTransactionModel(boolean commit) {}

    /** AvaTax {@code VoidTransactionModel}. */
    record VoidTransactionModel(String code) {}

    /** AvaTax {@code TransactionModel} response (subset). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransactionModel(
            Long id,
            String code,
            String status,
            BigDecimal totalAmount,
            BigDecimal totalTax,
            BigDecimal totalTaxable,
            List<TransactionLineModel> lines) {}

    /** AvaTax {@code TransactionLineModel} response (subset). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransactionLineModel(
            String lineNumber,
            BigDecimal lineAmount,
            BigDecimal tax,
            BigDecimal taxableAmount,
            List<TransactionLineDetailModel> details) {}

    /** AvaTax {@code TransactionLineDetailModel} response (subset). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransactionLineDetailModel(
            String jurisdictionType,
            String jurisCode,
            String jurisName,
            BigDecimal rate,
            BigDecimal tax,
            String taxName) {}
}
