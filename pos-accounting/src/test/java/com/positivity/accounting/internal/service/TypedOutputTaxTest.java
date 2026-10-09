package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.ExtInvoiceTax;
import com.positivity.accounting.internal.entity.GLMapping;
import com.positivity.accounting.internal.entity.InvoiceGlPosting;
import com.positivity.accounting.internal.entity.MappingKey;
import com.positivity.accounting.internal.entity.PostingCategory;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.IdempotencyOutcome;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.repository.ExtInvoiceTaxRepository;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.accounting.internal.repository.InvoiceGlPostingRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.MappingKeyRepository;
import com.positivity.accounting.internal.repository.PostingCategoryRepository;
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * CAP:550 S32d item 11 (AW50): output tax by type. The CAD keys and accounts below are fixture data, as the currency
 * template would provision them; no assertion depends on Canada beyond that data.
 */
@DisplayName("TypedOutputTax - output tax by type and the AW50 hold (CAP:550 S32d)")
class TypedOutputTaxTest {

    private static final UUID CATEGORY = UUID.fromString("0199b000-0000-7000-8000-00000000ca01");
    private static final UUID INVOICE = UUID.fromString("0199b000-0000-7000-8000-00000000ca02");
    private static final UUID A2200 = UUID.fromString("0199b000-0000-7000-8000-000000002200");
    private static final UUID A2210 = UUID.fromString("0199b000-0000-7000-8000-000000002210");
    private static final UUID A2220 = UUID.fromString("0199b000-0000-7000-8000-000000002220");
    private static final UUID A2230 = UUID.fromString("0199b000-0000-7000-8000-000000002230");
    private static final UUID AR = UUID.fromString("0199b000-0000-7000-8000-000000001200");
    private static final UUID REVENUE = UUID.fromString("0199b000-0000-7000-8000-000000004000");
    private static final LocalDateTime DATE = LocalDateTime.of(2026, 10, 8, 12, 0);
    private static final LocalDateTime TEMPLATE_START = LocalDateTime.of(2020, 1, 1, 0, 0);

    private final PostingCategoryRepository categories = mock(PostingCategoryRepository.class);
    private final MappingKeyRepository keys = mock(MappingKeyRepository.class);
    private final GLMappingRepository mappings = mock(GLMappingRepository.class);
    private final ExtInvoiceTaxRepository rows = mock(ExtInvoiceTaxRepository.class);
    private final InvoiceGlPostingRepository invoicePostings = mock(InvoiceGlPostingRepository.class);
    private final List<MappingKey> tenantKeys = new ArrayList<>();
    private final Map<UUID, List<GLMapping>> tenantMappings = new java.util.HashMap<>();

    private TypedOutputTax typedOutputTax;

    @BeforeEach
    void setUp() {
        PostingCategory category = new PostingCategory(CATEGORY);
        category.setCategoryName("INVOICE_REVENUE");
        when(categories.findByCategoryName("INVOICE_REVENUE")).thenReturn(Optional.of(category));
        when(keys.findByPostingCategory_PostingCategoryId(CATEGORY)).thenReturn(tenantKeys);
        when(mappings.findAllEffectiveMappings(eq(CATEGORY), any(), any())).thenAnswer(call -> {
            LocalDateTime date = call.getArgument(2);
            return tenantMappings.getOrDefault(call.<UUID>getArgument(1), List.of()).stream()
                    .filter(mapping -> mapping.isEffectiveOn(date))
                    .toList();
        });
        when(mappings.findByMappingKey_MappingKeyId(any()))
                .thenAnswer(call -> tenantMappings.getOrDefault(call.<UUID>getArgument(0), List.of()));
        typedOutputTax =
                new TypedOutputTax(categories, keys, mappings, rows, invoicePostings, new LedgerCurrency("CAD"));
        map("SERVICE_REVENUE", REVENUE, TEMPLATE_START, null);
        map("SALES_TAX_PAYABLE", A2200, TEMPLATE_START, null);
    }

    /** The CAD fixture keys: GST and HST to 2210, QST to 2220, PST to 2230. */
    private void mapTypedKeys() {
        map("SALES_TAX_PAYABLE_GST", A2210, TEMPLATE_START, null);
        map("SALES_TAX_PAYABLE_HST", A2210, TEMPLATE_START, null);
        map("SALES_TAX_PAYABLE_QST", A2220, TEMPLATE_START, null);
        map("SALES_TAX_PAYABLE_PST", A2230, TEMPLATE_START, null);
    }

    private void map(String keyName, UUID account, LocalDateTime start, LocalDateTime end) {
        MappingKey key = new MappingKey();
        key.setMappingKeyId(UUID.nameUUIDFromBytes(keyName.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        key.setKeyName(keyName);
        tenantKeys.add(key);
        GLMapping mapping = new GLMapping();
        mapping.setGlAccountId(account);
        mapping.setEffectiveStartDate(start);
        mapping.setEffectiveEndDate(end);
        tenantMappings
                .computeIfAbsent(key.getMappingKeyId(), id -> new ArrayList<>())
                .add(mapping);
    }

    private void rows(ExtInvoiceTax... taxRows) {
        when(rows.findByInvoiceId(INVOICE)).thenReturn(List.of(taxRows));
    }

    private static ExtInvoiceTax row(String type, String amount) {
        return ExtInvoiceTax.builder()
                .invoiceId(INVOICE)
                .jurisdictionType("FIXTURE")
                .jurisdictionCode("Z1")
                .rate(BigDecimal.ZERO)
                .taxableBase(new BigDecimal("1000.00"))
                .taxAmount(new BigDecimal(amount))
                .taxType(type)
                .build();
    }

    @Test
    @DisplayName("AC 1: a tenant without typed keys posts untyped, whatever its rows say")
    void tenantWithoutTypedKeysIsUntyped() {
        rows(row("GST", "50.00"));

        assertThat(typedOutputTax.planInvoice(INVOICE, new BigDecimal("50.00"), DATE))
                .isInstanceOf(TypedOutputTax.Plan.Untyped.class);
        assertThat(typedOutputTax.typedAccounts(DATE)).isEmpty();
        verify(rows, never()).findByInvoiceId(any());
    }

    @Test
    @DisplayName("AC 12: GST and PST rows post one leg each, to 2210 and 2230")
    void gstAndPstPostSeparately() {
        mapTypedKeys();
        rows(row("GST", "50.00"), row("PST", "70.00"));

        TypedOutputTax.Plan plan = typedOutputTax.planInvoice(INVOICE, new BigDecimal("120.00"), DATE);

        assertThat(plan).isInstanceOf(TypedOutputTax.Plan.Typed.class);
        assertThat(((TypedOutputTax.Plan.Typed) plan).legs())
                .containsExactly(
                        new TypedOutputTax.Leg("GST", A2210, new BigDecimal("50.00")),
                        new TypedOutputTax.Leg("PST", A2230, new BigDecimal("70.00")));
    }

    @Test
    @DisplayName("AC 13: one untyped row holds the whole invoice; no default account is used")
    void untypedRowHolds() {
        mapTypedKeys();
        rows(row("GST", "50.00"), row(null, "70.00"));

        assertThat(typedOutputTax.planInvoice(INVOICE, new BigDecimal("120.00"), DATE))
                .isInstanceOfSatisfying(
                        TypedOutputTax.Plan.TaxTypeMissing.class,
                        missing ->
                                assertThat(missing.detail()).contains("70.00").contains("without a tax type"));
    }

    @Test
    @DisplayName("AC 13: typed rows that do not account for the whole tax hold the invoice")
    void typedRowsShortOfTheTaxHold() {
        mapTypedKeys();
        rows(row("GST", "50.00"));

        assertThat(typedOutputTax.planInvoice(INVOICE, new BigDecimal("120.00"), DATE))
                .isInstanceOf(TypedOutputTax.Plan.TaxTypeMissing.class);
    }

    @Test
    @DisplayName("AC 13: a tax type without a mapped key holds the invoice; no type is inferred")
    void typeWithoutKeyHolds() {
        mapTypedKeys();
        rows(row("GST", "50.00"), row("LEVY", "5.00"));

        assertThat(typedOutputTax.planInvoice(INVOICE, new BigDecimal("55.00"), DATE))
                .isInstanceOfSatisfying(
                        TypedOutputTax.Plan.TaxTypeMissing.class,
                        missing -> assertThat(missing.detail()).contains("SALES_TAX_PAYABLE_LEVY"));
    }

    @Test
    @DisplayName("Exempt (zero) rows without a type do not hold the invoice")
    void zeroUntypedRowsAreIgnored() {
        mapTypedKeys();
        rows(row("GST", "50.00"), row(null, "0.00"));

        assertThat(typedOutputTax.planInvoice(INVOICE, new BigDecimal("50.00"), DATE))
                .isInstanceOf(TypedOutputTax.Plan.Typed.class);
    }

    @Test
    @DisplayName(
            "ADR-0067: each leg HALF_UP at the exponent, the rounding cent on the largest leg, legs sum to the tax")
    void legsSumExactlyToTheTax() {
        mapTypedKeys();
        rows(row("GST", "1.005"), row("PST", "1.005"));

        TypedOutputTax.Plan plan = typedOutputTax.planInvoice(INVOICE, new BigDecimal("2.01"), DATE);

        assertThat(((TypedOutputTax.Plan.Typed) plan).legs())
                .extracting(TypedOutputTax.Leg::amount)
                .containsExactly(new BigDecimal("1.00"), new BigDecimal("1.01"));
    }

    /** The invoice's open revenue posting, made by type or not. */
    private void invoicePosted(boolean byType) {
        when(invoicePostings.findByInvoiceIdAndReversalJournalEntryIdIsNull(INVOICE))
                .thenReturn(Optional.of(InvoiceGlPosting.builder()
                        .invoiceId(INVOICE)
                        .journalEntryId(UUID.randomUUID())
                        .taxPostedByType(byType)
                        .build()));
    }

    @Test
    @DisplayName("R3.1: an invoice posted untyped earlier is credited untyped, though the tenant has typed keys now")
    void creditFollowsAnUntypedPosting() {
        mapTypedKeys();
        rows(row("GST", "50.00"), row("PST", "70.00"));
        invoicePosted(false);

        assertThat(typedOutputTax.planCredit(INVOICE, new BigDecimal("12.00"), DATE))
                .isInstanceOf(TypedOutputTax.Plan.Untyped.class);
    }

    @Test
    @DisplayName("AC 13 [M] / R3.1: a credit against a held invoice is TAX_TYPE_MISSING; after its reprocess it splits")
    void creditAgainstAHeldInvoiceWaitsForItsReprocess() {
        mapTypedKeys();
        rows(row("GST", "50.00"), row("PST", "70.00"));
        when(invoicePostings.findByInvoiceIdAndReversalJournalEntryIdIsNull(INVOICE))
                .thenReturn(Optional.empty());

        assertThat(typedOutputTax.planCredit(INVOICE, new BigDecimal("12.00"), DATE))
                .isInstanceOf(TypedOutputTax.Plan.TaxTypeMissing.class);

        invoicePosted(true);
        assertThat(typedOutputTax.planCredit(INVOICE, new BigDecimal("12.00"), DATE))
                .isInstanceOf(TypedOutputTax.Plan.Typed.class);
    }

    @Test
    @DisplayName("R3.1: a tenant without typed keys credits an unposted invoice untyped, as before S32d")
    void untypedTenantCreditsAnUnpostedInvoiceUntyped() {
        when(invoicePostings.findByInvoiceIdAndReversalJournalEntryIdIsNull(INVOICE))
                .thenReturn(Optional.empty());

        assertThat(typedOutputTax.planCredit(INVOICE, new BigDecimal("12.00"), DATE))
                .isInstanceOf(TypedOutputTax.Plan.Untyped.class);
    }

    @Test
    @DisplayName("A credit reverses each type in the share the invoice collected it")
    void creditSplitsByCollectedShare() {
        mapTypedKeys();
        invoicePosted(true);
        rows(row("GST", "50.00"), row("PST", "70.00"));

        TypedOutputTax.Plan plan = typedOutputTax.planCredit(INVOICE, new BigDecimal("12.00"), DATE);

        assertThat(((TypedOutputTax.Plan.Typed) plan).legs())
                .containsExactly(
                        new TypedOutputTax.Leg("GST", A2210, new BigDecimal("5.00")),
                        new TypedOutputTax.Leg("PST", A2230, new BigDecimal("7.00")));
    }

    @Test
    @DisplayName("AC 13: a credit against an invoice with untyped tax is refused alike")
    void creditAgainstUntypedTaxIsMissing() {
        mapTypedKeys();
        invoicePosted(true);
        rows(row(null, "120.00"));

        assertThat(typedOutputTax.planCredit(INVOICE, new BigDecimal("12.00"), DATE))
                .isInstanceOf(TypedOutputTax.Plan.TaxTypeMissing.class);
    }

    @Test
    @DisplayName("AC 12: the reconciliation's accounts are every tax-payable key's, SALES_TAX_PAYABLE included")
    void taxPayableAccountsInForce() {
        mapTypedKeys();
        // A typed mapping that ended before the period is not in force in it.
        map("SALES_TAX_PAYABLE_OLD", UUID.randomUUID(), TEMPLATE_START, LocalDateTime.of(2025, 1, 1, 0, 0));

        assertThat(typedOutputTax.taxPayableAccountsInForce(
                        LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 10, 31, 23, 59)))
                .containsExactlyInAnyOrder(A2200, A2210, A2220, A2230)
                .doesNotContain(REVENUE);
    }

    /**
     * AC 13 end to end through the real posting path: held while untyped, posted by type on the audited reprocess once
     * the typed fact replaced the rows, and never posted twice.
     */
    @Nested
    @DisplayName("Hold and reprocess (AC 13)")
    class HoldAndReprocess {

        private final Clock clock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);
        private final GLMappingResolver resolver = mock(GLMappingResolver.class);
        private final GLPostingService glPosting = mock(GLPostingService.class);
        private final InvoiceGlPostingRepository postings = mock(InvoiceGlPostingRepository.class);
        private final JournalEntryRepository journalEntries = mock(JournalEntryRepository.class);
        private final ObjectMapper objectMapper =
                JsonMapper.builder().findAndAddModules().build();
        private InvoiceRevenuePostingService postingService;
        private InvoiceRevenueReprocessor reprocessor;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUpPosting() {
            mapTypedKeys();
            ObjectProvider<com.positivity.accounting.internal.config.OutboxEventWriter> writer =
                    mock(ObjectProvider.class);
            postingService = new InvoiceRevenuePostingService(
                    clock,
                    resolver,
                    glPosting,
                    postings,
                    writer,
                    TestZoneResolvers.utc(clock),
                    typedOutputTax,
                    journalEntries);
            reprocessor = new InvoiceRevenueReprocessor(
                    postingService, journalEntries, objectMapper, mock(PlatformTransactionManager.class));
            when(resolver.resolveGLAccount(eq("INVOICE_REVENUE"), eq("ACCOUNTS_RECEIVABLE"), any()))
                    .thenReturn(AR);
            when(resolver.resolveGLAccount(eq("INVOICE_REVENUE"), eq("SERVICE_REVENUE"), any()))
                    .thenReturn(REVENUE);
            when(postings.findByInvoiceIdAndReversalJournalEntryIdIsNull(INVOICE))
                    .thenReturn(Optional.empty());
            when(postings.save(any(InvoiceGlPosting.class))).thenAnswer(call -> call.getArgument(0));
        }

        private InvoiceUpdatedV1 finalized() {
            return new InvoiceUpdatedV1(
                    INVOICE,
                    "INV-CA-1",
                    UUID.randomUUID(),
                    null,
                    null,
                    "party-1",
                    "FINALIZED",
                    new BigDecimal("1000.00"),
                    new BigDecimal("120.00"),
                    new BigDecimal("1120.00"),
                    BigDecimal.ZERO,
                    Instant.parse("2026-10-07T20:00:00Z"),
                    Instant.parse("2026-10-08T10:00:00Z"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        @Test
        @DisplayName("Untyped: nothing posts and the fact is held TAX_TYPE_MISSING; the reprocess posts it by type")
        void heldThenPostedOnReprocess() {
            rows(row("GST", "50.00"), row(null, "70.00"));

            FactPostingOutcome first = postingService.postRevenue(finalized());

            assertThat(first)
                    .isInstanceOfSatisfying(
                            FactPostingOutcome.Held.class,
                            held -> assertThat(held.reason()).isEqualTo(PostingFailureReason.TAX_TYPE_MISSING));
            verify(glPosting, never())
                    .postInvoiceRevenue(any(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(glPosting, never())
                    .postInvoiceRevenueByTaxType(any(), any(), any(), any(), any(), any(), any(), any());
            verify(postings, never()).save(any());

            // Still untyped on reprocess: held again.
            Map<String, Object> stored = objectMapper.convertValue(finalized(), Map.class);
            InvoiceRevenueReprocessor.Result still = reprocessor.reprocess(stored);
            assertThat(still.status()).isEqualTo(AccountingEventStatus.SUSPENDED);
            assertThat(still.reason()).isEqualTo("TAX_TYPE_MISSING");

            // The typed fact replaced the rows; the audited reprocess now posts by type.
            rows(row("GST", "50.00"), row("PST", "70.00"));
            UUID entry = UUID.randomUUID();
            when(glPosting.postInvoiceRevenueByTaxType(any(), any(), any(), any(), any(), any(), any(), anyString()))
                    .thenReturn(entry);

            InvoiceRevenueReprocessor.Result result = reprocessor.reprocess(stored);

            assertThat(result.status()).isEqualTo(AccountingEventStatus.PROCESSED);
            assertThat(result.idempotencyOutcome()).isEqualTo(IdempotencyOutcome.NEW);
            assertThat(result.journalEntryId()).isEqualTo(entry);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<GLPostingService.TaxLeg>> legs = ArgumentCaptor.forClass(List.class);
            verify(glPosting)
                    .postInvoiceRevenueByTaxType(
                            any(),
                            eq(INVOICE),
                            eq(AR),
                            eq(REVENUE),
                            eq(new BigDecimal("1000.00")),
                            legs.capture(),
                            any(),
                            anyString());
            assertThat(legs.getValue())
                    .containsExactly(
                            new GLPostingService.TaxLeg(A2210, new BigDecimal("50.00"), "GST"),
                            new GLPostingService.TaxLeg(A2230, new BigDecimal("70.00"), "PST"));
            ArgumentCaptor<InvoiceGlPosting> saved = ArgumentCaptor.forClass(InvoiceGlPosting.class);
            verify(postings).save(saved.capture());
            assertThat(saved.getValue().getTaxAmount()).isEqualByComparingTo("120.00");
            assertThat(saved.getValue().getRevenueAmount()).isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("A reprocess after another fact posted the cycle posts nothing twice")
        void reprocessAfterThePostingIsADuplicate() {
            UUID earlier = UUID.randomUUID();
            when(postings.findByInvoiceIdAndReversalJournalEntryIdIsNull(INVOICE))
                    .thenReturn(Optional.of(InvoiceGlPosting.builder()
                            .invoiceId(INVOICE)
                            .journalEntryId(earlier)
                            .build()));

            InvoiceRevenueReprocessor.Result result =
                    reprocessor.reprocess(objectMapper.convertValue(finalized(), Map.class));

            assertThat(result.status()).isEqualTo(AccountingEventStatus.PROCESSED);
            assertThat(result.idempotencyOutcome()).isEqualTo(IdempotencyOutcome.DUPLICATE_IGNORED);
            assertThat(result.journalEntryId()).isEqualTo(earlier);
            verify(glPosting, never())
                    .postInvoiceRevenueByTaxType(any(), any(), any(), any(), any(), any(), any(), any());
        }
    }
}
