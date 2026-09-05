package com.positivity.accounting.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseContractIntegrationTest;
import com.positivity.accounting.internal.dto.AccountingEventSubmitRequest;
import com.positivity.accounting.internal.entity.ExtCustomerParty;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.Vendor;
import com.positivity.accounting.internal.enums.VendorStatus;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.ExtCustomerPartyRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.VendorRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Contract behavior for the accounting-event payload display projection (issue #1778).
 *
 * <p>The event detail response keeps its raw payload byte-for-byte — it is an audit artifact —
 * and adds {@code payloadReferences}, a typed projection naming the UUID-backed values inside it.
 * These tests pin both halves of the contract: the projection resolves what accounting knows, and
 * leaves what it does not know as null rather than echoing the UUID. They also pin the
 * non-regression the issue insists on: the payload itself, and the ids callers route and audit
 * with, are unchanged.
 */
@DisplayName("Accounting Event Payload Reference Contract Tests (issue #1778)")
class AccountingEventPayloadReferenceContractBehaviorIT extends BaseContractIntegrationTest {

    private static final String API_V1_EVENTS = "/v1/accounting/events";

    private static final UUID ORGANIZATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f1000");
    private static final UUID KNOWN_INVOICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f1001");
    private static final UUID UNKNOWN_INVOICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f1002");
    private static final UUID KNOWN_CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f1003");
    private static final UUID KNOWN_VENDOR_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f1004");

    private static final String INVOICE_NUMBER = "INV-2026-004417";
    private static final String CUSTOMER_NAME = "Northside Fleet Services";
    private static final String CUSTOMER_NUMBER = "C-10427";
    private static final String VENDOR_NAME = "Acme Parts Supply";

    @Autowired
    private AccountingEventRepository accountingEventRepository;

    @Autowired
    private ExtInvoiceRepository extInvoiceRepository;

    @Autowired
    private ExtCustomerPartyRepository extCustomerPartyRepository;

    @Autowired
    private VendorRepository vendorRepository;

    @BeforeEach
    void setUp() {
        accountingEventRepository.deleteAll();
        extInvoiceRepository.deleteAll();
        extCustomerPartyRepository.deleteAll();
        vendorRepository.deleteAll();

        extInvoiceRepository.save(ExtInvoice.builder()
                .invoiceId(KNOWN_INVOICE_ID)
                .invoiceNumber(INVOICE_NUMBER)
                .partyId(KNOWN_CUSTOMER_ID.toString())
                .status("FINALIZED")
                .total(new BigDecimal("110.00"))
                .tax(new BigDecimal("10.00"))
                .aggregateVersion(1L)
                .updatedAt(Instant.now(Clock.systemUTC()))
                .build());

        extCustomerPartyRepository.save(ExtCustomerParty.builder()
                .partyId(KNOWN_CUSTOMER_ID)
                .partyType("COMMERCIAL")
                .displayName(CUSTOMER_NAME)
                .customerNumber(CUSTOMER_NUMBER)
                .status("ACTIVE")
                .aggregateVersion(1L)
                .updatedAt(Instant.now(Clock.systemUTC()))
                .build());

        Vendor vendor = new Vendor();
        vendor.setVendorId(KNOWN_VENDOR_ID);
        vendor.setName(VENDOR_NAME);
        vendor.setVendorNumber("V-8801");
        vendor.setStatus(VendorStatus.ACTIVE);
        vendor.setCreatedAt(Instant.now(Clock.systemUTC()));
        vendor.setUpdatedAt(Instant.now(Clock.systemUTC()));
        vendorRepository.save(vendor);
    }

    @AfterEach
    void tearDown() {
        accountingEventRepository.deleteAll();
        extInvoiceRepository.deleteAll();
        extCustomerPartyRepository.deleteAll();
        vendorRepository.deleteAll();
    }

    @Test
    @DisplayName("Detail response projects recognized payload references with their display values")
    void detailProjectsResolvedPayloadReferences() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invoiceId", KNOWN_INVOICE_ID.toString());
        payload.put("customerId", KNOWN_CUSTOMER_ID.toString());
        payload.put("billDetails", Map.of("vendorId", KNOWN_VENDOR_ID.toString()));
        payload.put("amount", 110.00);

        UUID eventId = submitEvent(payload);

        // Paths are relative to the stored payload, which is the whole submitted envelope: the
        // producer's own payload is nested under its "payload" key, and the envelope's
        // organizationId is a reference in its own right.
        mockMvc.perform(withAuth(get(API_V1_EVENTS + "/" + eventId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadReferences.length()").value(4))
                // Ordered by path: organizationId, payload.billDetails.vendorId,
                // payload.customerId, payload.invoiceId.
                .andExpect(jsonPath("$.payloadReferences[0].path").value("organizationId"))
                .andExpect(jsonPath("$.payloadReferences[0].referenceType").value("ORGANIZATION"))
                .andExpect(jsonPath("$.payloadReferences[1].path").value("payload.billDetails.vendorId"))
                .andExpect(jsonPath("$.payloadReferences[1].referenceType").value("VENDOR"))
                .andExpect(jsonPath("$.payloadReferences[1].id").value(KNOWN_VENDOR_ID.toString()))
                .andExpect(jsonPath("$.payloadReferences[1].displayName").value(VENDOR_NAME))
                .andExpect(jsonPath("$.payloadReferences[2].path").value("payload.customerId"))
                .andExpect(jsonPath("$.payloadReferences[2].referenceType").value("CUSTOMER"))
                .andExpect(jsonPath("$.payloadReferences[2].displayName").value(CUSTOMER_NAME))
                .andExpect(jsonPath("$.payloadReferences[2].displayReference").value(CUSTOMER_NUMBER))
                .andExpect(jsonPath("$.payloadReferences[3].path").value("payload.invoiceId"))
                .andExpect(jsonPath("$.payloadReferences[3].referenceType").value("INVOICE"))
                .andExpect(jsonPath("$.payloadReferences[3].displayReference").value(INVOICE_NUMBER));
    }

    @Test
    @DisplayName("An unresolvable reference is projected with null display values, never the UUID")
    void detailProjectsUnresolvedReferenceWithoutDisplayValues() throws Exception {
        // organizationId can never resolve (ADR-0023 left no organization directory) and this
        // invoice is not in the replica: two references accounting cannot name.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invoiceId", UNKNOWN_INVOICE_ID.toString());

        UUID eventId = submitEvent(payload);

        String body = mockMvc.perform(withAuth(get(API_V1_EVENTS + "/" + eventId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadReferences[?(@.path=='payload.invoiceId')].id")
                        .value(UNKNOWN_INVOICE_ID.toString()))
                .andExpect(jsonPath("$.payloadReferences[?(@.path=='payload.invoiceId')].displayName")
                        .isEmpty())
                .andExpect(jsonPath("$.payloadReferences[?(@.path=='payload.invoiceId')].displayReference")
                        .isEmpty())
                // organizationId can never resolve today, and is projected with no display values
                // rather than omitted or filled with its own UUID.
                .andExpect(jsonPath("$.payloadReferences[?(@.path=='organizationId')].id")
                        .value(ORGANIZATION_ID.toString()))
                .andExpect(jsonPath("$.payloadReferences[?(@.path=='organizationId')].displayName")
                        .isEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // The defect being fixed is a UUID rendered as a label: prove no display key holds one.
        assertThat(body).doesNotContain("\"displayName\":\"" + UNKNOWN_INVOICE_ID + "\"");
        assertThat(body).doesNotContain("\"displayReference\":\"" + UNKNOWN_INVOICE_ID + "\"");
        assertThat(body).doesNotContain("\"displayName\":\"" + ORGANIZATION_ID + "\"");
        assertThat(body).doesNotContain("\"displayReference\":\"" + ORGANIZATION_ID + "\"");
    }

    @Test
    @DisplayName("The raw payload is returned unchanged alongside the projection")
    void rawPayloadIsUnchanged() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invoiceId", KNOWN_INVOICE_ID.toString());
        payload.put("description", "Invoice finalized");
        payload.put("lineItems", List.of(Map.of("sku", "BRK-4410", "quantity", 2)));

        UUID eventId = submitEvent(payload);

        mockMvc.perform(withAuth(get(API_V1_EVENTS + "/" + eventId)))
                .andExpect(status().isOk())
                // Every original value survives, untouched and un-substituted — the payload is the
                // audit record and the projection sits beside it, not in it.
                .andExpect(jsonPath("$.payload.payload.invoiceId").value(KNOWN_INVOICE_ID.toString()))
                .andExpect(jsonPath("$.payload.payload.description").value("Invoice finalized"))
                .andExpect(jsonPath("$.payload.payload.lineItems[0].sku").value("BRK-4410"))
                .andExpect(jsonPath("$.payload.payload.lineItems[0].quantity").value(2))
                // ... and nothing was added to it.
                .andExpect(jsonPath("$.payload.payloadReferences").doesNotExist())
                .andExpect(jsonPath("$.payload.payload.payloadReferences").doesNotExist())
                .andExpect(jsonPath("$.payload.payload.displayName").doesNotExist())
                // Routing and audit fields are untouched.
                .andExpect(jsonPath("$.eventId").value(eventId.toString()));
    }

    @Test
    @DisplayName("A producer payload with no recognized reference projects only the envelope's own")
    void payloadWithoutReferencesProjectsOnlyEnvelopeReferences() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amount", 100.00);
        payload.put("description", "No identifiers here");

        UUID eventId = submitEvent(payload);

        // Nothing in the producer's payload is a reference, so the only entry is the envelope's
        // organizationId — projected, but with no display values, since nothing can name it.
        mockMvc.perform(withAuth(get(API_V1_EVENTS + "/" + eventId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadReferences.length()").value(1))
                .andExpect(jsonPath("$.payloadReferences[0].path").value("organizationId"))
                .andExpect(jsonPath("$.payloadReferences[0].displayName").doesNotExist())
                .andExpect(jsonPath("$.payloadReferences[0].displayReference").doesNotExist())
                .andExpect(jsonPath("$.payload.payload.amount").value(100.00));
    }

    @Test
    @DisplayName("List responses stay lean and omit the projection")
    void listResponseOmitsProjection() throws Exception {
        submitEvent(new HashMap<>(Map.of("invoiceId", KNOWN_INVOICE_ID.toString())));

        mockMvc.perform(withAuth(get(API_V1_EVENTS)).param("organizationId", ORGANIZATION_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].payloadReferences").doesNotExist());
    }

    private UUID submitEvent(Map<String, Object> payload) throws Exception {
        AccountingEventSubmitRequest request = AccountingEventSubmitRequest.builder()
                .organizationId(ORGANIZATION_ID)
                .eventType("INVOICE_FINALIZED")
                .payload(payload)
                .build();

        MvcResult result = mockMvc.perform(withAuth(post(API_V1_EVENTS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn();

        var response = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return UUID.fromString(response.get("eventId").toString());
    }
}
