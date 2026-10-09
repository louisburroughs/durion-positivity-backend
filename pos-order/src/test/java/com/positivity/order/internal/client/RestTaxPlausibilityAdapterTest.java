package com.positivity.order.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.order.internal.client.TaxPlausibilityPort.Checked;
import com.positivity.order.internal.client.TaxPlausibilityPort.Disagreement;
import com.positivity.order.internal.client.TaxPlausibilityPort.Implausible;
import com.positivity.order.internal.client.TaxPlausibilityPort.PlausibilityQuery;
import com.positivity.order.internal.client.TaxPlausibilityPort.StatedAmount;
import com.positivity.order.internal.client.TaxPlausibilityPort.Unavailable;
import com.positivity.shared.error.ApiError;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/** CAP:550 S32d item 6: pos-tax's answers are classified on their code, never on the status alone. */
@DisplayName("RestTaxPlausibilityAdapter (CAP:550 S32d)")
class RestTaxPlausibilityAdapterTest {

    private static final String BASE = "http://pos-tax:8091";
    private MockRestServiceServer server;
    private RestTaxPlausibilityAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new RestTaxPlausibilityAdapter(
                builder.build(), JsonMapper.builder().build());
        MockHttpServletRequest inbound = new MockHttpServletRequest();
        inbound.addHeader("X-Correlation-Id", "corr-1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(inbound));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static PlausibilityQuery query() {
        return new PlausibilityQuery(
                "CA",
                "QC",
                "Z1Z 1Z1",
                "Springfield",
                LocalDate.of(2026, 10, 15),
                "CAD",
                new BigDecimal("40.00"),
                List.of(new StatedAmount("GST_HST", new BigDecimal("4.60"))),
                "000000000RT0001");
    }

    @Test
    @DisplayName("a 200 is Checked, with the service authority and the inbound correlation id forwarded")
    void checked() {
        server.expect(requestTo(BASE + RestTaxPlausibilityAdapter.PLAUSIBILITY_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Correlation-Id", "corr-1"))
                .andExpect(header("X-Authorities", "tax:rates:view"))
                .andRespond(withSuccess("""
                        {"outcome":"PLAUSIBLE","ratesUsed":[],"maximums":[],"supplierRegistrationRequired":true,
                         "supplierRegistrationNumberWellFormed":null,"asOf":"2026-10-15","source":"STUB"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(adapter.check(query())).isEqualTo(new Checked("PLAUSIBLE", true, null));
        server.verify();
    }

    @Test
    @DisplayName("only 422 TAX_AMOUNT_IMPLAUSIBLE is Implausible, with pos-tax's field errors")
    void implausible() {
        server.expect(requestTo(BASE + RestTaxPlausibilityAdapter.PLAUSIBILITY_PATH))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"TAX_AMOUNT_IMPLAUSIBLE","message":"implausible","status":422,
                                 "fieldErrors":[{"field":"statedTaxes[0].amount","message":"at most 5.21"}]}
                                """));

        assertThat(adapter.check(query()))
                .isEqualTo(new Implausible(
                        "implausible", List.of(new ApiError.FieldError("statedTaxes[0].amount", "at most 5.21"))));
    }

    @Test
    @DisplayName("[M] another 422 code is a Disagreement, never relayed")
    void otherCodeIsADisagreement() {
        server.expect(requestTo(BASE + RestTaxPlausibilityAdapter.PLAUSIBILITY_PATH))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"TAX_REGIME_NOT_DECLARED\",\"message\":\"m\",\"status\":422}"));

        assertThat(adapter.check(query())).isEqualTo(new Disagreement(422, "TAX_REGIME_NOT_DECLARED"));
    }

    @Test
    @DisplayName("a 400 without a body is a Disagreement without a code; a 5xx is Unavailable")
    void badRequestAndServerError() {
        server.expect(requestTo(BASE + RestTaxPlausibilityAdapter.PLAUSIBILITY_PATH))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThat(adapter.check(query())).isEqualTo(new Disagreement(400, null));

        server.reset();
        server.expect(requestTo(BASE + RestTaxPlausibilityAdapter.PLAUSIBILITY_PATH))
                .andRespond(withServerError());
        assertThat(adapter.check(query())).isInstanceOf(Unavailable.class);
    }

    @Test
    @DisplayName("unreachable is Unavailable")
    void unreachable() {
        server.expect(requestTo(BASE + RestTaxPlausibilityAdapter.PLAUSIBILITY_PATH))
                .andRespond(request -> {
                    throw new java.net.ConnectException("refused");
                });

        assertThat(adapter.check(query())).isInstanceOf(Unavailable.class);
    }

    @Test
    @DisplayName("the drawer receipt's evidence rule is read; another document type's or a failure gives empty")
    void evidenceRule() {
        server.expect(requestTo(
                        BASE + RestTaxPlausibilityAdapter.EVIDENCE_RULES_PATH + "?countryCode=CA&asOf=2026-10-15"))
                .andExpect(header("X-Correlation-Id", "corr-1"))
                .andRespond(withSuccess("""
                        {"countryCode":"CA","asOf":"2026-10-15","currency":"CAD","source":"STUB",
                         "rules":[{"rule":"SUPPLIER_REGISTRATION_NUMBER","fromAmount":500.00,"appliesTo":["VENDOR_BILL"]},
                                  {"rule":"SUPPLIER_REGISTRATION_NUMBER","fromAmount":100.00,
                                   "appliesTo":["DRAWER_RECEIPT","VENDOR_BILL"]}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(adapter.drawerEvidenceRule("CA", LocalDate.of(2026, 10, 15))).hasValueSatisfying(rule -> {
            assertThat(rule.threshold()).isEqualByComparingTo("100.00");
            assertThat(rule.currencyCode()).isEqualTo("CAD");
        });

        server.reset();
        server.expect(requestTo(
                        BASE + RestTaxPlausibilityAdapter.EVIDENCE_RULES_PATH + "?countryCode=CA&asOf=2026-10-15"))
                .andRespond(withServerError());
        assertThat(adapter.drawerEvidenceRule("CA", LocalDate.of(2026, 10, 15))).isEmpty();
    }

    @Test
    @DisplayName("the query's toString never prints the supplier's number")
    void queryToStringRedacts() {
        assertThat(query().toString())
                .doesNotContain("000000000RT0001")
                .contains("supplierRegistrationNumberProvided=true");
    }
}
