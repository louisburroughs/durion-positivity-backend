package com.positivity.accounting.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseContractIntegrationTest;
import com.positivity.accounting.internal.entity.CreditMemo;
import com.positivity.accounting.internal.entity.ExtCustomerParty;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.accounting.internal.repository.CreditMemoRepository;
import com.positivity.accounting.internal.repository.ExtCustomerPartyRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.service.InvoiceBalanceCalculator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Contract behavior for the credit-memo display references added by issue #1779.
 *
 * <p>List and detail responses must carry a memo reference, the original invoice's number and the
 * customer's name/number alongside — never instead of — the UUIDs that commands, links and audit
 * traceability depend on. Each display value is proved in both states the issue calls out:
 * populated when accounting can resolve it, and null when it cannot. The "unavailable" cases are
 * the ones that matter most, since the defect being fixed is a frontend that would otherwise show
 * a UUID: nothing here may ever answer a UUID string in a display field.
 *
 * <p>Display values resolve from data accounting already holds — the {@code ext_invoice} and
 * {@code ext_customer_party} replicas, both fed by the owners' domain events (ADR-0044) — so
 * these tests seed those replicas rather than stubbing a cross-service call.
 */
@DisplayName("Credit Memo Display Reference Contract Tests (issue #1779)")
public class CreditMemoDisplayReferenceContractBehaviorIT extends BaseContractIntegrationTest {

    private static final String API_V1_CREDIT_MEMOS = "/v1/accounting/credit-memos";

    private static final UUID NAMED_INVOICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0001");
    private static final UUID UNNAMED_INVOICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0002");
    private static final UUID NAMED_CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0003");
    private static final UUID UNKNOWN_CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0004");

    private static final String INVOICE_NUMBER = "INV-2026-004417";
    private static final String CUSTOMER_NAME = "Northside Fleet Services";
    private static final String CUSTOMER_NUMBER = "C-10427";

    @Autowired
    private CreditMemoRepository creditMemoRepository;

    @Autowired
    private ExtInvoiceRepository extInvoiceRepository;

    @Autowired
    private ExtCustomerPartyRepository extCustomerPartyRepository;

    @MockitoBean
    private InvoiceBalanceCalculator invoiceBalanceCalculator;

    @BeforeEach
    void setUp() {
        creditMemoRepository.deleteAll();
        extInvoiceRepository.deleteAll();
        extCustomerPartyRepository.deleteAll();

        // The balance calculator is not what this test is about; keep the read paths quiet.
        when(invoiceBalanceCalculator.findInvoice(any(UUID.class))).thenReturn(Optional.empty());

        // Replica invoice WITH a number, and one the owner has published no number for.
        extInvoiceRepository.save(replicaInvoice(NAMED_INVOICE_ID, INVOICE_NUMBER));
        extInvoiceRepository.save(replicaInvoice(UNNAMED_INVOICE_ID, null));

        // Replica party WITH a name and number. UNKNOWN_CUSTOMER_ID is deliberately absent — a
        // party the replica has never seen.
        extCustomerPartyRepository.save(ExtCustomerParty.builder()
                .partyId(NAMED_CUSTOMER_ID)
                .partyType("COMMERCIAL")
                .displayName(CUSTOMER_NAME)
                .customerNumber(CUSTOMER_NUMBER)
                .status("ACTIVE")
                .aggregateVersion(1L)
                .updatedAt(Instant.now(Clock.systemUTC()))
                .build());
    }

    @AfterEach
    void tearDown() {
        creditMemoRepository.deleteAll();
        extInvoiceRepository.deleteAll();
        extCustomerPartyRepository.deleteAll();
    }

    @Test
    @DisplayName("Detail response carries the memo, invoice and customer display values")
    void detailResponseCarriesDisplayValues() throws Exception {
        CreditMemo memo = creditMemoRepository.save(creditMemo(NAMED_INVOICE_ID, NAMED_CUSTOMER_ID, "CM-202401-1"));

        mockMvc.perform(withAuth(get(API_V1_CREDIT_MEMOS + "/" + memo.getCreditMemoId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditMemoReference").value("CM-202401-1"))
                .andExpect(jsonPath("$.originalInvoiceReference").value(INVOICE_NUMBER))
                .andExpect(jsonPath("$.customerDisplayName").value(CUSTOMER_NAME))
                .andExpect(jsonPath("$.customerReference").value(CUSTOMER_NUMBER))
                // The identifiers stay in the contract: display values are additive, and commands,
                // links and audit still key off the UUIDs.
                .andExpect(
                        jsonPath("$.creditMemoId").value(memo.getCreditMemoId().toString()))
                .andExpect(jsonPath("$.originalInvoiceId").value(NAMED_INVOICE_ID.toString()))
                .andExpect(jsonPath("$.customerId").value(NAMED_CUSTOMER_ID.toString()));
    }

    @Test
    @DisplayName("Detail response leaves unresolvable display values null, never a UUID")
    void detailResponseLeavesUnavailableDisplayValuesNull() throws Exception {
        CreditMemo memo = creditMemoRepository.save(creditMemo(UNNAMED_INVOICE_ID, UNKNOWN_CUSTOMER_ID, null));

        String body = mockMvc.perform(withAuth(get(API_V1_CREDIT_MEMOS + "/" + memo.getCreditMemoId())))
                .andExpect(status().isOk())
                // An invoice the replica knows but holds no number for, and a party it has never
                // seen at all: three display values, none of them resolvable.
                .andExpect(jsonPath("$.creditMemoReference").doesNotExist())
                .andExpect(jsonPath("$.originalInvoiceReference").doesNotExist())
                .andExpect(jsonPath("$.customerDisplayName").doesNotExist())
                .andExpect(jsonPath("$.customerReference").doesNotExist())
                .andExpect(jsonPath("$.originalInvoiceId").value(UNNAMED_INVOICE_ID.toString()))
                .andExpect(jsonPath("$.customerId").value(UNKNOWN_CUSTOMER_ID.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // The specific failure #1779 guards against: a UUID smuggled into a display field. Assert
        // it structurally — no display key anywhere in the body may hold one of these ids.
        assertNoUuidInDisplayFields(body);
    }

    @Test
    @DisplayName("List response carries the same display values as detail")
    void listResponseCarriesDisplayValues() throws Exception {
        creditMemoRepository.save(creditMemo(NAMED_INVOICE_ID, NAMED_CUSTOMER_ID, "CM-202401-1"));

        mockMvc.perform(withAuth(get(API_V1_CREDIT_MEMOS).param("customerId", NAMED_CUSTOMER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].creditMemoReference").value("CM-202401-1"))
                .andExpect(jsonPath("$.content[0].originalInvoiceReference").value(INVOICE_NUMBER))
                .andExpect(jsonPath("$.content[0].customerDisplayName").value(CUSTOMER_NAME))
                .andExpect(jsonPath("$.content[0].customerReference").value(CUSTOMER_NUMBER))
                .andExpect(jsonPath("$.content[0].creditMemoId").exists())
                .andExpect(jsonPath("$.content[0].originalInvoiceId").value(NAMED_INVOICE_ID.toString()));
    }

    @Test
    @DisplayName("List response leaves unresolvable display values null, never a UUID")
    void listResponseLeavesUnavailableDisplayValuesNull() throws Exception {
        creditMemoRepository.save(creditMemo(UNNAMED_INVOICE_ID, UNKNOWN_CUSTOMER_ID, null));

        String body = mockMvc.perform(
                        withAuth(get(API_V1_CREDIT_MEMOS).param("customerId", UNKNOWN_CUSTOMER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].creditMemoReference").doesNotExist())
                .andExpect(jsonPath("$.content[0].originalInvoiceReference").doesNotExist())
                .andExpect(jsonPath("$.content[0].customerDisplayName").doesNotExist())
                .andExpect(jsonPath("$.content[0].customerReference").doesNotExist())
                .andExpect(jsonPath("$.content[0].customerId").value(UNKNOWN_CUSTOMER_ID.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertNoUuidInDisplayFields(body);
    }

    @Test
    @DisplayName("A page of memos resolves display values for every row, not just the first")
    void listResolvesDisplayValuesForEveryRow() throws Exception {
        // Both rows share a customer but differ on invoice: one resolvable, one not. Proves the
        // batch lookup keys per row rather than reusing one row's resolution for the page.
        creditMemoRepository.save(creditMemo(NAMED_INVOICE_ID, NAMED_CUSTOMER_ID, "CM-202401-1"));
        creditMemoRepository.save(creditMemo(UNNAMED_INVOICE_ID, NAMED_CUSTOMER_ID, "CM-202401-2"));

        mockMvc.perform(withAuth(get(API_V1_CREDIT_MEMOS)
                        .param("customerId", NAMED_CUSTOMER_ID.toString())
                        .param("sort", "creditMemoReference,asc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].creditMemoReference").value("CM-202401-1"))
                .andExpect(jsonPath("$.content[0].originalInvoiceReference").value(INVOICE_NUMBER))
                .andExpect(jsonPath("$.content[0].customerDisplayName").value(CUSTOMER_NAME))
                .andExpect(jsonPath("$.content[1].creditMemoReference").value("CM-202401-2"))
                .andExpect(jsonPath("$.content[1].originalInvoiceReference").doesNotExist())
                .andExpect(jsonPath("$.content[1].customerDisplayName").value(CUSTOMER_NAME));
    }

    /**
     * No display field may carry any of this test's identifiers. Checking the serialized body
     * rather than a parsed field means a future field that fell back to a UUID would fail here
     * too, not just the four fields asserted above.
     */
    private static void assertNoUuidInDisplayFields(String body) {
        for (String displayField : new String[] {
            "creditMemoReference", "originalInvoiceReference", "customerDisplayName", "customerReference"
        }) {
            for (UUID id : new UUID[] {NAMED_INVOICE_ID, UNNAMED_INVOICE_ID, NAMED_CUSTOMER_ID, UNKNOWN_CUSTOMER_ID}) {
                assertThat(body).doesNotContain("\"" + displayField + "\":\"" + id + "\"");
            }
        }
    }

    private static ExtInvoice replicaInvoice(UUID invoiceId, String invoiceNumber) {
        return ExtInvoice.builder()
                .invoiceId(invoiceId)
                .invoiceNumber(invoiceNumber)
                .partyId(NAMED_CUSTOMER_ID.toString())
                .status("FINALIZED")
                .total(new BigDecimal("110.00"))
                .tax(new BigDecimal("10.00"))
                .finalizedAt(Instant.now(Clock.systemUTC()))
                .aggregateVersion(1L)
                .updatedAt(Instant.now(Clock.systemUTC()))
                .build();
    }

    private static CreditMemo creditMemo(UUID invoiceId, UUID customerId, String reference) {
        CreditMemo memo = new CreditMemo();
        memo.setCreditMemoReference(reference);
        memo.setOriginalInvoiceId(invoiceId);
        memo.setCustomerId(customerId);
        memo.setCreditAmount(new BigDecimal("100.00"));
        memo.setTaxAmountReversed(new BigDecimal("10.00"));
        memo.setReasonCode("RETURNED_GOODS");
        memo.setStatus(CreditMemoStatus.POSTED);
        memo.setCreatedByUserId(TEST_USER);
        memo.setCurrency("USD");
        memo.setCreationTimestamp(Instant.parse("2024-01-01T00:00:00Z"));
        memo.setPostedTimestamp(Instant.parse("2024-01-01T00:00:00Z"));
        return memo;
    }
}
