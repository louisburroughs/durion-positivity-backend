package com.positivity.accounting.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.accounting.internal.dto.InformationReturnFormsResponse;
import com.positivity.accounting.internal.dto.TaxPurchaseRules;
import com.positivity.accounting.internal.dto.TaxTypesReference;
import com.positivity.accounting.internal.dto.TaxUseQuote;
import com.positivity.accounting.internal.exception.TaxQuoteRefusedException;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.tenancy.TenantContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP:550 #2615 at the wire: the pos-tax utility client sends the service identity, the tenant and the correlation id;
 * reads the information-return forms; answers 503 when pos-tax refuses (any 4xx), is unreachable, failing or silent
 * past the read timeout.
 */
@DisplayName("TaxReferenceClient — pos-accounting's reads of pos-tax's configured references (#2615)")
class TaxReferenceClientTest {

    private static final String BASE = "http://pos-tax:8091";
    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-0000000000f2");
    private static final String URL = BASE + "/v1/tax/information-return-forms?countryCode=ZZ";
    private static final String FORMS = """
            {"countryCode":"ZZ","source":"STUB","forms":[
              {"form":"ZZ_FORM_A","label":"Form A","boxes":[{"box":"1","label":"Box one"},{"box":"2","label":"Box two"}],
               "payeeIdSchemes":["ZZ_BUSINESS_ID","ZZ_PERSON_ID"]}]}
            """;

    private MockRestServiceServer server;
    private TaxReferenceClient client;

    private TaxReferenceClient client() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new TaxReferenceClient(builder, new ObjectMapper(), BASE);
    }

    @BeforeEach
    void setUp() {
        client = client();
        TenantContext.bind(TENANT);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("sends X-User pos-accounting, X-Authorities tax:rates:view, the tenant and the correlation id, and"
            + " reads the forms")
    void readsTheForms() {
        MockHttpServletRequest inbound = new MockHttpServletRequest();
        inbound.addHeader("X-Correlation-Id", "corr-2615");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(inbound));
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-accounting"))
                .andExpect(header("X-Authorities", "tax:rates:view"))
                .andExpect(header("X-Tenant-Id", TENANT.toString()))
                .andExpect(header("X-Correlation-Id", "corr-2615"))
                .andRespond(withSuccess(FORMS, MediaType.APPLICATION_JSON));

        InformationReturnFormsResponse forms = client.informationReturnForms("ZZ");

        assertThat(forms.countryCode()).isEqualTo("ZZ");
        assertThat(forms.source()).isEqualTo("STUB");
        assertThat(forms.forms()).singleElement().satisfies(form -> {
            assertThat(form.form()).isEqualTo("ZZ_FORM_A");
            assertThat(form.hasBox("2")).isTrue();
            assertThat(form.hasBox("99")).isFalse();
            assertThat(form.payeeIdSchemes()).containsExactly("ZZ_BUSINESS_ID", "ZZ_PERSON_ID");
        });
        server.verify();
    }

    @Test
    @DisplayName("ADR-0017: any pos-tax 4xx (400, 404, 422, 403) is 503, never relayed; WARN with status and code only")
    void a4xxIsUnavailable() {
        Logger logger = (Logger) LoggerFactory.getLogger(TaxReferenceClient.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            for (HttpStatus status :
                    new HttpStatus[] {HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND, HttpStatus.UNPROCESSABLE_CONTENT}) {
                client = client();
                server.expect(requestTo(URL))
                        .andRespond(withStatus(status)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body("""
                                        {"code":"VALIDATION_ERROR","message":"countryCode ZZ secret-detail",
                                         "status":%d,"fieldErrors":[{"field":"countryCode","message":"ZZ is bad"}]}
                                        """.formatted(status.value())));

                assertThatThrownBy(() -> client.informationReturnForms("ZZ"))
                        .as("%s", status)
                        .isInstanceOf(TaxServiceUnavailableException.class);
            }
        } finally {
            logger.detachAppender(logs);
        }
        assertThat(logs.list).hasSize(3).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("code VALIDATION_ERROR")
                    .doesNotContain("secret-detail")
                    .doesNotContain("ZZ");
        });
        assertThat(logs.list.getFirst().getFormattedMessage()).contains("status 400");
    }

    @Test
    @DisplayName("pos-tax unreachable, a 5xx, a 403 (this service's authority) or an unreadable error is 503")
    void unavailable() {
        server.expect(requestTo(URL)).andRespond(request -> {
            throw new IOException("Connection refused");
        });
        assertThatThrownBy(() -> client.informationReturnForms("ZZ"))
                .isInstanceOf(TaxServiceUnavailableException.class);

        client = client();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.informationReturnForms("ZZ"))
                .isInstanceOf(TaxServiceUnavailableException.class);

        client = client();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> client.informationReturnForms("ZZ"))
                .isInstanceOf(TaxServiceUnavailableException.class);

        client = client();
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("not json"));
        assertThatThrownBy(() -> client.informationReturnForms("ZZ"))
                .isInstanceOf(TaxServiceUnavailableException.class);
    }

    @Test
    @DisplayName("a pos-tax that accepts the connection but never answers is 503 after the read timeout")
    void readTimeoutIsUnavailable() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) {
            TaxReferenceClient timed = new TaxReferenceClient(
                    RestClient.builder(),
                    new ObjectMapper(),
                    "http://localhost:" + silent.getLocalPort(),
                    Duration.ofSeconds(2),
                    Duration.ofMillis(300));
            long started = System.nanoTime();

            assertThatThrownBy(() -> timed.informationReturnForms("ZZ"))
                    .isInstanceOf(TaxServiceUnavailableException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    @DisplayName("S43: the purchase-tax rules read sends the country and asOf with tax:rates:view, and reads the"
            + " answer, configured false included")
    void readsThePurchaseRules() {
        server.expect(requestTo(BASE + "/v1/tax/purchase-rules?countryCode=ZZ&asOf=2026-10-01"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-accounting"))
                .andExpect(header("X-Authorities", "tax:rates:view"))
                .andExpect(header("X-Tenant-Id", TENANT.toString()))
                .andRespond(withSuccess("""
                        {"countryCode":"ZZ","asOf":"2026-10-01","source":"STUB","configured":true,
                         "taxOnResaleGoods":"HOLD","selfAssessUntaxedExpenses":true}
                        """, MediaType.APPLICATION_JSON));

        TaxPurchaseRules rules = client.purchaseRules("ZZ", LocalDate.of(2026, 10, 1));

        assertThat(rules.holdsTaxOnResaleGoods()).isTrue();
        assertThat(rules.selfAssessesUntaxedExpenses()).isTrue();
        assertThat(rules.asOf()).isEqualTo(LocalDate.of(2026, 10, 1));
        server.verify();

        client = client();
        server.expect(requestTo(BASE + "/v1/tax/purchase-rules?countryCode=MX&asOf=2026-10-01"))
                .andRespond(withSuccess("""
                        {"countryCode":"MX","asOf":"2026-10-01","source":"STUB","configured":false,
                         "taxOnResaleGoods":"ALLOW","selfAssessUntaxedExpenses":false}
                        """, MediaType.APPLICATION_JSON));
        TaxPurchaseRules none = client.purchaseRules("MX", LocalDate.of(2026, 10, 1));
        assertThat(none.holdsTaxOnResaleGoods()).isFalse();
        assertThat(none.selfAssessesUntaxedExpenses()).isFalse();
    }

    @Test
    @DisplayName("S43 AW49: a purchase-rules read pos-tax cannot answer (a 404 before the stub is deployed, a 5xx) is"
            + " 503, never read as off")
    void purchaseRulesUnavailable() {
        server.expect(requestTo(BASE + "/v1/tax/purchase-rules?countryCode=ZZ&asOf=2026-10-01"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> client.purchaseRules("ZZ", LocalDate.of(2026, 10, 1)))
                .isInstanceOf(TaxServiceUnavailableException.class);

        client = client();
        server.expect(requestTo(BASE + "/v1/tax/purchase-rules?countryCode=ZZ&asOf=2026-10-01"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> client.purchaseRules("ZZ", LocalDate.of(2026, 10, 1)))
                .isInstanceOf(TaxServiceUnavailableException.class);
    }

    @Test
    @DisplayName("S43: the use-tax quote posts calculationType USE with tax:calculate, and reads the line taxes; a 422"
            + " from the closed list is relayed with its code; any other 422, a 400 or a 501 is 503")
    void quotesUseTax() {
        TaxUseQuote.Request request = new TaxUseQuote.Request(
                List.of(new TaxUseQuote.Line("1", "Bill INV-43 line 1", BigDecimal.ONE, new BigDecimal("200.00"))),
                new TaxUseQuote.Address("ZZ", null, "00000"),
                "USD",
                "USE",
                "2026-10-01",
                UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4e01"),
                false);
        server.expect(requestTo(BASE + "/v1/tax/calculate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User", "pos-accounting"))
                .andExpect(header("X-Authorities", "tax:calculate"))
                .andExpect(header("X-Tenant-Id", TENANT.toString()))
                .andExpect(jsonPath("$.calculationType").value("USE"))
                .andExpect(jsonPath("$.currencyCode").value("USD"))
                .andExpect(jsonPath("$.committable").value(false))
                .andExpect(jsonPath("$.transactionDate").value("2026-10-01"))
                .andExpect(jsonPath("$.destinationAddress.countryCode").value("ZZ"))
                .andExpect(jsonPath("$.destinationAddress.postalCode").value("00000"))
                .andExpect(jsonPath("$.destinationAddress.regionCode").doesNotExist())
                .andExpect(jsonPath("$.lineItems[0].lineItemId").value("1"))
                .andExpect(jsonPath("$.lineItems[0].unitPrice").value(200.00))
                .andRespond(withSuccess("""
                        {"subtotal":200.00,"totalTax":17.00,"total":217.00,"calculationType":"USE",
                         "lineItemTaxes":[{"lineItemId":"1","subtotal":200.00,"taxAmount":17.00,"total":217.00}]}
                        """, MediaType.APPLICATION_JSON));

        TaxUseQuote.Response answer = client.useTax(request);

        assertThat(answer.lineItemTaxes()).singleElement().satisfies(line -> {
            assertThat(line.lineItemId()).isEqualTo("1");
            assertThat(line.taxAmount()).isEqualByComparingTo("17.00");
        });
        server.verify();

        // #2604 ruling 4 (amended): the closed list of configuration 422s is relayed with its code, never pos-tax's
        // message; any other 422, a 400 and a 501 are 503.
        for (String code :
                new String[] {"TAX_JURISDICTION_NOT_CONFIGURED", "CURRENCY_NOT_SUPPORTED", "TAX_CAPABILITY_UNSUPPORTED"
                }) {
            client = client();
            server.expect(requestTo(BASE + "/v1/tax/calculate"))
                    .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"code\":\"" + code + "\",\"message\":\"pos-tax secret-detail ZZ 00000\"}"));
            assertThatThrownBy(() -> client.useTax(request))
                    .as(code)
                    .isInstanceOfSatisfying(TaxQuoteRefusedException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(code);
                        assertThat(TaxQuoteRefusedException.STATUS.value()).isEqualTo(422);
                        assertThat(e.getMessage()).doesNotContain("secret-detail");
                    });
        }
        record Answer(HttpStatus status, String code) {}
        for (Answer refusal : new Answer[] {
            new Answer(HttpStatus.UNPROCESSABLE_CONTENT, "AMOUNT_PRECISION_EXCEEDS_CURRENCY"),
            new Answer(HttpStatus.UNPROCESSABLE_CONTENT, null),
            new Answer(HttpStatus.BAD_REQUEST, "TAX_JURISDICTION_NOT_CONFIGURED"),
            new Answer(HttpStatus.NOT_IMPLEMENTED, "TAX_CALCULATION_TYPE_UNSUPPORTED")
        }) {
            client = client();
            server.expect(requestTo(BASE + "/v1/tax/calculate"))
                    .andRespond(withStatus(refusal.status())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(refusal.code() == null ? "not json" : "{\"code\":\"" + refusal.code() + "\"}"));
            assertThatThrownBy(() -> client.useTax(request))
                    .as("%s", refusal)
                    .isInstanceOf(TaxServiceUnavailableException.class);
        }

        // The purchase-rules read never relays: a configuration 422 there is 503 like any other refusal.
        client = client();
        server.expect(requestTo(BASE + "/v1/tax/purchase-rules?countryCode=ZZ&asOf=2026-10-01"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"TAX_JURISDICTION_NOT_CONFIGURED\"}"));
        assertThatThrownBy(() -> client.purchaseRules("ZZ", LocalDate.of(2026, 10, 1)))
                .isInstanceOf(TaxServiceUnavailableException.class);
    }

    @Test
    @DisplayName("#2659: the tax-types read sends the country with tax:rates:view, the tenant and the correlation id,"
            + " and reads the types and regimes as configured")
    void readsTheTaxTypes() {
        MockHttpServletRequest inbound = new MockHttpServletRequest();
        inbound.addHeader("X-Correlation-Id", "corr-2659");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(inbound));
        server.expect(requestTo(BASE + "/v1/tax/tax-types?countryCode=ZZ"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-accounting"))
                .andExpect(header("X-Authorities", "tax:rates:view"))
                .andExpect(header("X-Tenant-Id", TENANT.toString()))
                .andExpect(header("X-Correlation-Id", "corr-2659"))
                .andRespond(withSuccess("""
                        {"countryCode":"ZZ","currency":"XTS","source":"STUB",
                         "taxTypes":[{"taxType":"ZZ_LEVY","regime":"ZZ_REGIME_1","jurisdictionType":"COUNTRY",
                                      "inputTaxRecoverable":true,"futureField":1},
                                     {"taxType":"ZZ_LOCAL","regime":null,"jurisdictionType":"CITY",
                                      "inputTaxRecoverable":false}],
                         "regimes":[{"regime":"ZZ_REGIME_1","regions":["R1"]}]}
                        """, MediaType.APPLICATION_JSON));

        TaxTypesReference answer = client.taxTypes("ZZ");

        assertThat(answer.countryCode()).isEqualTo("ZZ");
        assertThat(answer.source()).isEqualTo("STUB");
        assertThat(answer.taxTypes())
                .containsExactly(
                        new TaxTypesReference.TaxType("ZZ_LEVY", "ZZ_REGIME_1", "COUNTRY", true),
                        new TaxTypesReference.TaxType("ZZ_LOCAL", null, "CITY", false));
        assertThat(answer.regimes()).containsExactly(new TaxTypesReference.Regime("ZZ_REGIME_1", List.of("R1")));
        server.verify();
    }

    @Test
    @DisplayName("#2659: any pos-tax 4xx or 5xx, or no body, on the tax-types read is 503, never relayed")
    void taxTypesNotAnAnswerIsUnavailable() {
        for (HttpStatus status : new HttpStatus[] {
            HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND, HttpStatus.UNPROCESSABLE_CONTENT, HttpStatus.BAD_GATEWAY
        }) {
            client = client();
            server.expect(requestTo(BASE + "/v1/tax/tax-types?countryCode=ZZ"))
                    .andRespond(withStatus(status)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"code\":\"VALIDATION_ERROR\"}"));
            assertThatThrownBy(() -> client.taxTypes("ZZ"))
                    .as("%s", status)
                    .isInstanceOf(TaxServiceUnavailableException.class);
        }

        client = client();
        server.expect(requestTo(BASE + "/v1/tax/tax-types?countryCode=ZZ")).andRespond(withSuccess());
        assertThatThrownBy(() -> client.taxTypes("ZZ")).isInstanceOf(TaxServiceUnavailableException.class);
    }
}
