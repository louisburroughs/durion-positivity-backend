package com.positivity.invoice.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.invoice.internal.repository.InvoiceRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * First Failsafe ITs for pos-invoice (plan §5): the module had zero integration
 * tests, so nothing proved the from-order idempotency that pos-order's checkout
 * saga depends on actually holds at the database — only mocks said so.
 *
 * <p>What is pinned, end to end through the real filter chain, service, JPA,
 * and H2:
 * <ul>
 * <li><b>From-order creation is idempotent on {@code orderId}.</b> The first
 * call is 201 with {@code existing=false}; a replay is 200 with
 * {@code existing=true}, the same invoice id, and <b>no second row</b>. This is
 * the contract that lets a checkout retry after a timeout without
 * double-invoicing (parity story C1/C2), and it is enforced by the database,
 * not by the caller.</li>
 * <li><b>Gateway auth is enforced by the real chain</b>: anonymous 401, wrong
 * authority 403 (ADR-0011/ADR-0014).</li>
 * <li><b>An invalid body persists nothing.</b></li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Order-invoice contract behavior — first Failsafe layer")
class OrderInvoiceContractBehaviorIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InvoiceRepository invoiceRepository;

    /**
     * Both authorities, because this helper serves both halves of the contract: creating an invoice
     * needs {@code invoice:manage} and reading one back needs {@code invoice:invoice:view} since
     * #1612 split the read surface out of the write permission. A caller exercising both holds both
     * — the four roles that write invoices are all granted the read code too.
     */
    private MockHttpServletRequestBuilder withInvoiceAuth(MockHttpServletRequestBuilder requestBuilder) {
        return requestBuilder
                .header("X-User", "contract-test-user")
                .header("X-Authorities", "invoice:manage,invoice:invoice:view");
    }

    private static String fromOrderBody(UUID orderId) {
        return """
                {"orderId":"%s","customerId":"%s","locationId":"%s",
                 "subtotal":91.00,"taxAmount":7.51,"totalAmount":98.51,
                 "lines":[{"orderLineId":"%s","description":"Brake pad","quantity":2,
                   "unitPrice":45.50,"amount":91.00,"taxAmount":7.51,"type":"PART"}]}""".formatted(orderId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    @DisplayName(
            "first from-order call creates (201, existing=false); replay returns the same invoice (200, existing=true)")
    void fromOrderIsIdempotentOnOrderId() throws Exception {
        UUID orderId = UUID.randomUUID();
        long before = invoiceRepository.count();

        MvcResult first = mockMvc.perform(withInvoiceAuth(post("/v1/invoices/from-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fromOrderBody(orderId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.existing").value(false))
                .andExpect(jsonPath("$.invoiceId").isNotEmpty())
                .andExpect(jsonPath("$.invoiceNumber").isNotEmpty())
                .andReturn();
        String firstInvoiceId = objectMapper
                .readTree(first.getResponse().getContentAsString())
                .path("invoiceId")
                .asString();

        // The retry a checkout makes after a timeout: same orderId, same payload.
        mockMvc.perform(withInvoiceAuth(post("/v1/invoices/from-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fromOrderBody(orderId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.existing").value(true))
                .andExpect(jsonPath("$.invoiceId").value(firstInvoiceId));

        // The idempotency the saga depends on is a database fact: exactly one new row.
        assertThat(invoiceRepository.count()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("distinct orders get distinct invoices")
    void distinctOrdersAreNotDeduplicated() throws Exception {
        long before = invoiceRepository.count();

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(withInvoiceAuth(post("/v1/invoices/from-order")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(fromOrderBody(UUID.randomUUID()))))
                    .andExpect(status().isCreated());
        }

        assertThat(invoiceRepository.count()).isEqualTo(before + 2);
    }

    @Test
    @DisplayName("a created invoice is readable back through the get endpoint")
    void createdInvoiceIsReadable() throws Exception {
        MvcResult created = mockMvc.perform(withInvoiceAuth(post("/v1/invoices/from-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fromOrderBody(UUID.randomUUID()))))
                .andExpect(status().isCreated())
                .andReturn();
        String invoiceId = objectMapper
                .readTree(created.getResponse().getContentAsString())
                .path("invoiceId")
                .asString();

        mockMvc.perform(withInvoiceAuth(get("/v1/invoices/{invoiceId}", invoiceId)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("rejects an invalid body with 400 before anything is persisted")
    void invalidBodyPersistsNothing() throws Exception {
        long before = invoiceRepository.count();

        // orderId, customerId, and the money fields are @NotNull on the shared DTO.
        mockMvc.perform(withInvoiceAuth(post("/v1/invoices/from-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subtotal\":100.00}")))
                .andExpect(status().isBadRequest());

        assertThat(invoiceRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("requires gateway auth: anonymous is 401, wrong authority is 403")
    void securityIsEnforced() throws Exception {
        mockMvc.perform(post("/v1/invoices/from-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fromOrderBody(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/v1/invoices/from-order")
                        .header("X-User", "contract-test-user")
                        .header("X-Authorities", "invoice:view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fromOrderBody(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }
}
