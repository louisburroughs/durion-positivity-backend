package com.positivity.accounting.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.accounting.internal.dto.InformationReturnFormsResponse;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.tenancy.TenantContext;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
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
}
