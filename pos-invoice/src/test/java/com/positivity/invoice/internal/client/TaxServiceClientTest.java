package com.positivity.invoice.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.invoice.internal.config.InvoiceCurrencySource;
import com.positivity.invoice.internal.exception.InvoiceTaxRefusedException;
import com.positivity.tax.common.dto.TaxCalculationRequest.TaxAddress;
import com.positivity.tax.common.dto.TaxLineItem;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** {@link TaxServiceClient} wire behaviour (#2644): the functional currency goes out, pos-tax's 422s come back translated. */
@DisplayName("TaxServiceClient — functional currency and pos-tax 422 translation (#2644)")
class TaxServiceClientTest {

    private static final String BASE = "http://pos-tax.test/v1/tax";

    private MockRestServiceServer server;
    private TaxServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TaxServiceClient(builder, new InvoiceCurrencySource("cad"), BASE);
    }

    private static TaxAddress montreal() {
        return TaxAddress.builder()
                .countryCode("CA")
                .regionCode("QC")
                .city("Montreal")
                .postalCode("H2X 1Y4")
                .build();
    }

    private static List<TaxLineItem> oneLine() {
        return List.of(TaxLineItem.builder()
                .lineItemId("1")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100.00"))
                .build());
    }

    @Test
    @DisplayName("sends the tenant's functional currency, normalised, never a literal")
    void sendsTheFunctionalCurrency() {
        server.expect(requestTo(BASE + "/calculate"))
                .andExpect(jsonPath("$.currencyCode").value("CAD"))
                .andRespond(withSuccess("{\"totalTax\":14.975,\"lineItemTaxes\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.calculateTax(oneLine(), montreal(), null)).isEqualByComparingTo("14.9750");
        server.verify();
    }

    @Test
    @DisplayName("a 422 CURRENCY_NOT_SUPPORTED from pos-tax is 422 INVOICE_TAX_CURRENCY_NOT_SUPPORTED here")
    void translatesCurrencyRefusal() {
        server.expect(requestTo(BASE + "/calculate"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"CURRENCY_NOT_SUPPORTED\",\"message\":\"CAD is not USD\",\"status\":422}"));

        assertThatThrownBy(() -> client.calculateTax(oneLine(), montreal(), null))
                .isInstanceOfSatisfying(InvoiceTaxRefusedException.class, e -> assertThat(e.getCode())
                        .isEqualTo(InvoiceTaxRefusedException.CURRENCY_NOT_SUPPORTED));
    }

    @Test
    @DisplayName("a 422 TAX_JURISDICTION_NOT_CONFIGURED from pos-tax is 422 INVOICE_TAX_JURISDICTION_NOT_CONFIGURED here")
    void translatesJurisdictionRefusal() {
        server.expect(requestTo(BASE + "/calculate"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"TAX_JURISDICTION_NOT_CONFIGURED\",\"message\":\"no rates\",\"status\":422}"));

        assertThatThrownBy(() -> client.calculateTax(oneLine(), montreal(), null))
                .isInstanceOfSatisfying(InvoiceTaxRefusedException.class, e -> assertThat(e.getCode())
                        .isEqualTo(InvoiceTaxRefusedException.JURISDICTION_NOT_CONFIGURED));
    }

    @Test
    @DisplayName("the currency source refuses a value that is not an ISO 4217 code")
    void currencySourceRefusesNonIso() {
        assertThatThrownBy(() -> new InvoiceCurrencySource("dollars")).isInstanceOf(IllegalStateException.class);
    }
}
