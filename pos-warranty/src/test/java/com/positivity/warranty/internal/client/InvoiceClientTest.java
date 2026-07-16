package com.positivity.warranty.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.warranty.internal.exception.WarrantyIntegrationException;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link InvoiceClientImpl} wire behavior against a {@link MockRestServiceServer}
 * (same lightweight pattern as pos-catalog's {@code CatalogClientBuilderTest}):
 * reads degrade gracefully (empty on 404 / HTTP error / transport failure),
 * writes fail loudly with {@link WarrantyIntegrationException}.
 */
class InvoiceClientTest {

    private static final String BASE = "http://invoice/v1/invoices";
    private static final UUID PARTY_ID = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID INVOICE_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID PAYMENT_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID ADJUSTMENT_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID REFUND_ID = UUID.fromString("55555555-5555-7555-8555-555555555555");

    private MockRestServiceServer server;
    private InvoiceClientImpl client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new InvoiceClientImpl(builder, "invoice", "/v1/invoices");
    }

    @Test
    void searchInvoiceLinesMapsRowsAndSendsServiceHeaders() {
        server.expect(requestTo(BASE + "/items/search?partyId=" + PARTY_ID))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-warranty-service"))
                .andExpect(header("X-Authorities", "invoice:manage"))
                .andRespond(withSuccess(
                        "[{\"invoiceId\":\"" + INVOICE_ID + "\",\"invoiceNumber\":\"INV-100\","
                                + "\"description\":\"Alternator\",\"amount\":199.99}]",
                        MediaType.APPLICATION_JSON));

        var lines = client.searchInvoiceLines(PARTY_ID, null);

        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().invoiceId()).isEqualTo(INVOICE_ID);
        assertThat(lines.getFirst().invoiceNumber()).isEqualTo("INV-100");
        server.verify();
    }

    @Test
    void searchInvoiceLinesDegradesToEmptyListOnHttpError() {
        server.expect(requestTo(BASE + "/items/search?partyId=" + PARTY_ID)).andRespond(withServerError());

        assertThat(client.searchInvoiceLines(PARTY_ID, null)).isEmpty();
        server.verify();
    }

    @Test
    void searchInvoiceLinesDegradesToEmptyListWhenServiceUnreachable() {
        server.expect(requestTo(BASE + "/items/search?partyId=" + PARTY_ID))
                .andRespond(withException(new IOException("connection refused")));

        assertThat(client.searchInvoiceLines(PARTY_ID, null)).isEmpty();
        server.verify();
    }

    @Test
    void getInvoiceMapsSummaryWithItems() {
        server.expect(requestTo(BASE + "/" + INVOICE_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"invoiceId\":\"" + INVOICE_ID + "\",\"invoiceNumber\":\"INV-100\","
                                + "\"partyId\":\"" + PARTY_ID + "\",\"status\":\"DRAFT\",\"total\":199.99,"
                                + "\"items\":[{\"id\":\"" + ADJUSTMENT_ID + "\",\"description\":\"Alternator\","
                                + "\"quantity\":1,\"unitPrice\":199.99,\"amount\":199.99}]}",
                        MediaType.APPLICATION_JSON));

        Optional<InvoiceClient.InvoiceSummary> summary = client.getInvoice(INVOICE_ID);

        assertThat(summary).isPresent();
        assertThat(summary.orElseThrow().invoiceNumber()).isEqualTo("INV-100");
        assertThat(summary.orElseThrow().items()).hasSize(1);
        server.verify();
    }

    @Test
    void getInvoiceDegradesToEmptyOn404() {
        server.expect(requestTo(BASE + "/" + INVOICE_ID)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.getInvoice(INVOICE_ID)).isEmpty();
        server.verify();
    }

    @Test
    void createAdjustmentPostsWarrantyTypeAndResolvesEntryByExternalReference() {
        server.expect(requestTo(BASE + "/" + INVOICE_ID + "/adjustments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User", "pos-warranty-service"))
                .andExpect(header("X-Authorities", "invoice:manage"))
                .andExpect(jsonPath("$.type").value("WARRANTY"))
                .andExpect(jsonPath("$.amount").value(80.0))
                .andExpect(jsonPath("$.reason").value("warranty credit"))
                .andExpect(jsonPath("$.authorizedBy").value("tech-1"))
                .andExpect(jsonPath("$.externalReference").value("WC-2026-000077"))
                .andRespond(withSuccess(
                        "{\"invoiceId\":\"" + INVOICE_ID + "\",\"adjustmentEntries\":["
                                + "{\"id\":\"" + REFUND_ID + "\",\"type\":\"DISCOUNT\"},"
                                + "{\"id\":\"" + ADJUSTMENT_ID + "\",\"type\":\"WARRANTY\","
                                + "\"externalReference\":\"WC-2026-000077\"}]}",
                        MediaType.APPLICATION_JSON));

        Optional<UUID> adjustmentId = client.createAdjustment(
                INVOICE_ID, new BigDecimal("80.0"), "warranty credit", "tech-1", "WC-2026-000077");

        assertThat(adjustmentId).contains(ADJUSTMENT_ID);
        server.verify();
    }

    @Test
    void createAdjustmentFailsLoudlyOnHttpError() {
        server.expect(requestTo(BASE + "/" + INVOICE_ID + "/adjustments"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.createAdjustment(
                        INVOICE_ID, new BigDecimal("80.0"), "warranty credit", "tech-1", "WC-2026-000077"))
                .isInstanceOf(WarrantyIntegrationException.class)
                .hasMessageContaining(INVOICE_ID.toString());
        server.verify();
    }

    @Test
    void createRefundReturnsRefundIdOnSuccess() {
        server.expect(requestTo(BASE + "/" + INVOICE_ID + "/payments/" + PAYMENT_ID + "/refunds"))
                .andExpect(method(HttpMethod.POST))
                // pos-invoice's refund service enforces REFUND_PAYMENT plus SUPERVISOR_OVERRIDE
                // for payments outside its 180-day window (typical for warranty claims).
                .andExpect(header("X-Authorities", "invoice:manage,REFUND_PAYMENT,SUPERVISOR_OVERRIDE"))
                .andExpect(jsonPath("$.reason").value("OTHER"))
                .andExpect(jsonPath("$.externalReference").value("WC-2026-000077"))
                .andRespond(withSuccess(
                        "{\"refundId\":\"" + REFUND_ID + "\",\"invoiceId\":\"" + INVOICE_ID
                                + "\",\"amount\":80.0,\"status\":\"COMPLETED\"}",
                        MediaType.APPLICATION_JSON));

        Optional<UUID> refundId =
                client.createRefund(INVOICE_ID, PAYMENT_ID, new BigDecimal("80.0"), null, "WC-2026-000077");

        assertThat(refundId).contains(REFUND_ID);
        server.verify();
    }

    @Test
    void createRefundWithNonCompletedStatusFailsLoudly() {
        // pos-invoice returns 201 with a refundId even when the gateway declines and the
        // RefundRecord is persisted FAILED — that must not count as a successful refund.
        server.expect(requestTo(BASE + "/" + INVOICE_ID + "/payments/" + PAYMENT_ID + "/refunds"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"refundId\":\"" + REFUND_ID + "\",\"invoiceId\":\"" + INVOICE_ID
                                + "\",\"amount\":80.0,\"status\":\"FAILED\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() ->
                        client.createRefund(INVOICE_ID, PAYMENT_ID, new BigDecimal("80.0"), null, "WC-2026-000077"))
                .isInstanceOf(WarrantyIntegrationException.class)
                .hasMessageContaining(REFUND_ID.toString())
                .hasMessageContaining("FAILED")
                .hasMessageContaining("customer was not refunded");
        server.verify();
    }

    @Test
    void createRefundFailsLoudlyWhenServiceUnreachable() {
        server.expect(requestTo(BASE + "/" + INVOICE_ID + "/payments/" + PAYMENT_ID + "/refunds"))
                .andRespond(withException(new IOException("connection refused")));

        assertThatThrownBy(() ->
                        client.createRefund(INVOICE_ID, PAYMENT_ID, new BigDecimal("80.0"), null, "WC-2026-000077"))
                .isInstanceOf(WarrantyIntegrationException.class)
                .hasMessageContaining(PAYMENT_ID.toString());
        server.verify();
    }
}
