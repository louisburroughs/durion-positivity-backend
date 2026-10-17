package com.positivity.supplier.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.supplier.internal.exception.SupplierServiceUnavailableException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The pos-tax registration-schemes read (#2623 tests): success, every failure answers 503 and writes nothing, the
 * WARN line carries the path and status only.
 */
@DisplayName("TaxRegistrationSchemeClient — pos-tax's registration-scheme list, fail-closed (#2623)")
class TaxRegistrationSchemeClientTest {

    private static final String BODY = """
            {"source":"STUB","schemes":[
              {"scheme":"GST_HST","countryCode":"CA","label":"GST/HST","holderType":"BUSINESS","regional":false,"regions":[]},
              {"scheme":"QST","countryCode":"CA","label":"QST","holderType":"BUSINESS","regional":true,"regions":["QC"]}
            ]}
            """;

    private MockRestServiceServer server;
    private TaxRegistrationSchemeClient client;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TaxRegistrationSchemeClient(builder, "pos-tax", "/v1/tax/registration-schemes");
        logger = (Logger) LoggerFactory.getLogger(TaxRegistrationSchemeClient.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    @DisplayName("reads the list with the service user and tax:rates:view, by country or for every country")
    void readsTheList() {
        server.expect(requestTo("http://pos-tax/v1/tax/registration-schemes?countryCode=CA"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-supplier-service"))
                .andExpect(header("X-Authorities", "tax:rates:view"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://pos-tax/v1/tax/registration-schemes"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        TaxRegistrationSchemes ca = client.fetch("CA");
        assertThat(ca.source()).isEqualTo("STUB");
        assertThat(ca.byCode()).containsOnlyKeys("GST_HST", "QST");
        assertThat(ca.byCode().get("QST").regional()).isTrue();
        assertThat(ca.byCode().get("QST").regions()).containsExactly("QC");
        assertThat(client.fetch(null).schemes()).hasSize(2);
        server.verify();
    }

    @Test
    @DisplayName("a 5xx, a 4xx, an empty body or an unreachable pos-tax is SupplierServiceUnavailableException")
    void failsClosed() {
        server.expect(requestTo("http://pos-tax/v1/tax/registration-schemes")).andRespond(withServerError());
        server.expect(requestTo("http://pos-tax/v1/tax/registration-schemes"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        server.expect(requestTo("http://pos-tax/v1/tax/registration-schemes"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://pos-tax/v1/tax/registration-schemes")).andRespond(request -> {
            throw new SocketTimeoutException("read timed out");
        });

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> client.fetch(null))
                    .isInstanceOf(SupplierServiceUnavailableException.class)
                    .satisfies(e -> assertThat(((SupplierServiceUnavailableException) e).getCode())
                            .isEqualTo("SERVICE_UNAVAILABLE"));
        }
        server.verify();
        assertThat(logs.list).hasSize(4).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("path=/v1/tax/registration-schemes")
                    .contains("status=")
                    .doesNotContain("schemes\"");
        });
    }
}
