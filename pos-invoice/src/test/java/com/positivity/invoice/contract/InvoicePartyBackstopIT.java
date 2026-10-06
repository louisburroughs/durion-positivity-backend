package com.positivity.invoice.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.invoice.internal.client.TaxLifecycleClient;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.PaymentIntent;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.enums.PaymentFlow;
import com.positivity.invoice.internal.enums.PaymentIntentStatus;
import com.positivity.invoice.internal.payment.GatewayPaymentResult;
import com.positivity.invoice.internal.payment.PaymentGatewayPort;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.internal.repository.PaymentIntentRepository;
import com.positivity.invoice.internal.service.InvoiceTaxCalculator;
import com.positivity.tenancy.testing.TenantTestSupport;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP:550 S9 (#2507, spec §4.4 item 1, §9.4, AW12) end to end through the real filter chain,
 * controllers, advice, services and JPA: finalisation and payments refuse an invoice without a
 * customer with 422 {@code INVOICE_PARTY_REQUIRED} before any tax document is created or any
 * gateway call is made, while an invoice on the tenant's CASH house account finalises and pays in
 * full. The gateway and the tax calculator are the two outbound ports and are stubbed; everything
 * between the HTTP request and the H2 row is real.
 */
@SpringBootTest(
        properties = {
            // Own in-memory database: this module runs test classes concurrently and
            // OrderInvoiceContractBehaviorIT counts invoice rows on the shared `pos_invoice_test` DB.
            "spring.datasource.url=jdbc:h2:mem:pos_invoice_party_backstop;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Invoice party backstops (CAP:550 S9)")
class InvoicePartyBackstopIT {

    /** A party id for the tenant's CASH house account; pos-invoice never looks it up to take a payment. */
    private static final UUID CASH_PARTY = UUID.fromString("01980a58-0000-7000-8000-0000000000ca");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @MockitoBean
    private PaymentGatewayPort gatewayPort;

    @MockitoBean
    private InvoiceTaxCalculator invoiceTaxCalculator;

    @MockitoBean
    private TaxLifecycleClient taxLifecycleClient;

    @BeforeEach
    void stubOutboundPorts() {
        // Nothing taxable: no provider document, no commit — the finalisation path stays local.
        when(invoiceTaxCalculator.calculateCommittable(any())).thenReturn(null);
        when(gatewayPort.saleCapture(any())).thenAnswer(inv -> successfulResult(new BigDecimal("98.51")));
        when(gatewayPort.capture(any())).thenAnswer(inv -> successfulResult(new BigDecimal("10.00")));
    }

    private static GatewayPaymentResult successfulResult(BigDecimal amount) {
        return new GatewayPaymentResult() {
            @Override
            public boolean isSuccessful() {
                return true;
            }

            @Override
            public boolean isUnknown() {
                return false;
            }

            @Override
            public BigDecimal getAmount() {
                return amount;
            }

            @Override
            public String getGatewayReference() {
                return "gw-ref-" + UUID.randomUUID();
            }

            @Override
            public String getGatewayProvider() {
                return "stub";
            }

            @Override
            public String getRawResponse() {
                return "{}";
            }
        };
    }

    private static MockHttpServletRequestBuilder withAuthorities(
            MockHttpServletRequestBuilder request, String authorities) {
        return request.header("X-User", "backstop-test-user").header("X-Authorities", authorities);
    }

    private static String fromOrderBody(UUID orderId, UUID customerId) {
        return """
                {"orderId":"%s","customerId":"%s","locationId":"%s",
                 "subtotal":91.00,"taxAmount":7.51,"totalAmount":98.51,
                 "lines":[{"orderLineId":"%s","description":"Brake pad","quantity":2,
                   "unitPrice":45.50,"amount":91.00,"taxAmount":7.51,"type":"PART"}]}""".formatted(orderId, customerId, UUID.randomUUID(), UUID.randomUUID());
    }

    private static String saleCaptureBody(String idempotencyKey) {
        return """
                {"paymentFlow":"SALE_CAPTURE","amount":98.51,"idempotencyKey":"%s","paymentToken":"tok_test"}""".formatted(idempotencyKey);
    }

    private UUID createFromOrder(UUID customerId) throws Exception {
        MvcResult created = mockMvc.perform(withAuthorities(
                        post("/v1/invoices/from-order")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(fromOrderBody(UUID.randomUUID(), customerId)),
                        "invoice:manage"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(created.getResponse().getContentAsString())
                .path("invoiceId")
                .asString());
    }

    /** A DRAFT invoice with no customer, as orders checked out before S8 could leave behind. */
    private Invoice seedPartyLessDraft() {
        return TenantTestSupport.asTenant(TenantTestSupport.TENANT_A, () -> {
            Invoice invoice = new Invoice();
            invoice.setInvoiceNumber(
                    "INV-PARTYLESS-" + UUID.randomUUID().toString().substring(0, 8));
            invoice.setStatus(InvoiceStatus.DRAFT);
            invoice.setPartyId(null);
            invoice.setSubtotal(new BigDecimal("10.00"));
            invoice.setTax(new BigDecimal("0.00"));
            invoice.setTotal(new BigDecimal("10.00"));
            invoice.setAdjustmentsAmount(BigDecimal.ZERO);
            return invoiceRepository.save(invoice);
        });
    }

    private Invoice reload(UUID invoiceId) {
        return TenantTestSupport.asTenant(
                TenantTestSupport.TENANT_A,
                () -> invoiceRepository.findById(invoiceId).orElseThrow());
    }

    @Test
    @DisplayName("AC4: an invoice on the CASH house account finalises and is paid in full")
    void cashPartyInvoiceFinalisesAndPays() throws Exception {
        UUID invoiceId = createFromOrder(CASH_PARTY);

        mockMvc.perform(withAuthorities(
                        post("/v1/invoices/{invoiceId}/finalize", invoiceId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"),
                        "invoice:finalize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINALIZED"));

        mockMvc.perform(withAuthorities(
                        post("/v1/invoices/{invoiceId}/payments", invoiceId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(saleCaptureBody("idem-cash-" + invoiceId)),
                        "invoice:payment:process"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CAPTURED"));

        Invoice invoice = reload(invoiceId);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.FINALIZED);
        assertThat(invoice.getPartyId()).isEqualTo(CASH_PARTY.toString());
        verify(gatewayPort).saleCapture(any());
    }

    @Test
    @DisplayName("AC1: finalising a DRAFT invoice without a customer answers 422, stays DRAFT, creates no tax document")
    void finalizeWithoutPartyIsRefused() throws Exception {
        Invoice draft = seedPartyLessDraft();

        mockMvc.perform(withAuthorities(
                        post("/v1/invoices/{invoiceId}/finalize", draft.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"),
                        "invoice:finalize"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INVOICE_PARTY_REQUIRED"))
                .andExpect(jsonPath("$.message")
                        .value("This invoice has no customer; set the customer before finalizing"));

        Invoice reloaded = reload(draft.getId());
        assertThat(reloaded.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(reloaded.getFinalizedAt()).isNull();
        verifyNoInteractions(invoiceTaxCalculator);
        verifyNoInteractions(taxLifecycleClient);
    }

    @Test
    @DisplayName(
            "AC2: initiating a payment on an invoice without a customer answers 422, no intent row, no gateway call")
    void initiatePaymentWithoutPartyIsRefused() throws Exception {
        Invoice draft = seedPartyLessDraft();
        long intentsBefore = paymentIntentRepository.count();

        mockMvc.perform(withAuthorities(
                        post("/v1/invoices/{invoiceId}/payments", draft.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(saleCaptureBody("idem-partyless-" + draft.getId())),
                        "invoice:payment:process"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INVOICE_PARTY_REQUIRED"));

        assertThat(paymentIntentRepository.count()).isEqualTo(intentsBefore);
        verifyNoInteractions(gatewayPort);
    }

    @Test
    @DisplayName(
            "AC3: capturing an AUTHORIZED hold on an invoice without a customer answers 422 and the hold stays AUTHORIZED")
    void captureWithoutPartyIsRefused() throws Exception {
        Invoice draft = seedPartyLessDraft();
        PaymentIntent authorized = TenantTestSupport.asTenant(TenantTestSupport.TENANT_A, () -> {
            PaymentIntent intent = new PaymentIntent();
            intent.setInvoice(draft);
            intent.setIdempotencyKey("idem-auth-" + draft.getId());
            intent.setStatus(PaymentIntentStatus.AUTHORIZED);
            intent.setPaymentFlow(PaymentFlow.AUTH_ONLY);
            intent.setPaymentToken("tok_test");
            intent.setAuthorizedAmount(new BigDecimal("10.00"));
            intent.setCapturedAmount(BigDecimal.ZERO);
            intent.setVoidedRemainderAmount(BigDecimal.ZERO);
            intent.setGatewayProvider("stub");
            intent.setGatewayReference("gw-auth-ref");
            return paymentIntentRepository.save(intent);
        });

        mockMvc.perform(withAuthorities(
                        post("/v1/invoices/{invoiceId}/payments/{paymentId}/capture", draft.getId(), authorized.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"amount":10.00,"captureIdempotencyKey":"cap-%s"}""".formatted(authorized.getId())),
                        "invoice:payment:capture"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INVOICE_PARTY_REQUIRED"));

        PaymentIntent reloaded = TenantTestSupport.asTenant(
                TenantTestSupport.TENANT_A,
                () -> paymentIntentRepository.findById(authorized.getId()).orElseThrow());
        assertThat(reloaded.getStatus()).isEqualTo(PaymentIntentStatus.AUTHORIZED);
        verify(gatewayPort, never()).capture(any());
    }
}
