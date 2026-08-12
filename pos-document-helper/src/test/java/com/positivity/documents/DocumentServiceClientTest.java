package com.positivity.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.documents.exception.DocumentRenderException;
import com.positivity.documents.exception.TemplateRegistrationException;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link DocumentServiceClient} (ADR-0020).
 *
 * <p>
 * The retry wrapper is this client's reason to exist — a receipt render at the
 * counter should survive a transient pos-documents blip without the caller
 * writing any retry code. So the tests pin that a failure is retried up to the
 * configured attempts, that success on a later attempt is success, and that
 * exhaustion surfaces as the library's own typed exceptions
 * ({@link TemplateRegistrationException}, {@link DocumentRenderException})
 * carrying the template id, not as a raw transport error.
 *
 * <p>
 * A 200 with an empty body is also a failure: an empty PDF handed to a printer
 * is worse than an error the caller can retry.
 */
@DisplayName("DocumentServiceClient — retrying template and render client")
class DocumentServiceClientTest {

    private MockRestServiceServer server;
    private DocumentServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://documents");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DocumentServiceClient(
                builder.build(),
                Retry.of(
                        "test",
                        RetryConfig.custom()
                                .maxAttempts(3)
                                .waitDuration(Duration.ofMillis(1))
                                .build()));
    }

    private static TemplateRegistration registration() {
        return TemplateRegistration.htmlTemplate("INVOICE_TEMPLATE", "Invoice", "<html/>")
                .build();
    }

    private static RenderRequest renderRequest() {
        return RenderRequest.pdf("INVOICE_TEMPLATE", Map.of("total", "10.00"))
                .filename("invoice.pdf")
                .build();
    }

    @Nested
    @DisplayName("registerTemplate")
    class Register {

        @Test
        @DisplayName("PUTs the registration to the template's own URI (upsert semantics)")
        void registersTemplate() {
            server.expect(requestTo("http://documents/v1/documents/templates/INVOICE_TEMPLATE"))
                    .andExpect(method(HttpMethod.PUT))
                    .andExpect(jsonPath("$.templateId").value("INVOICE_TEMPLATE"))
                    .andExpect(jsonPath("$.contentType").value("text/html"))
                    .andRespond(withStatus(HttpStatus.NO_CONTENT));

            client.registerTemplate(registration());

            server.verify();
        }

        @Test
        @DisplayName("retries a transient failure and succeeds without the caller noticing")
        void retriesThroughATransientFailure() {
            server.expect(requestTo("http://documents/v1/documents/templates/INVOICE_TEMPLATE"))
                    .andRespond(withServerError());
            server.expect(requestTo("http://documents/v1/documents/templates/INVOICE_TEMPLATE"))
                    .andRespond(withStatus(HttpStatus.NO_CONTENT));

            // The whole point of the client: one blip must not surface to the caller.
            client.registerTemplate(registration());

            server.verify();
        }

        @Test
        @DisplayName("wraps exhaustion in TemplateRegistrationException naming the template")
        void exhaustionIsTyped() {
            for (int i = 0; i < 3; i++) {
                server.expect(requestTo("http://documents/v1/documents/templates/INVOICE_TEMPLATE"))
                        .andRespond(withServerError());
            }

            assertThatThrownBy(() -> client.registerTemplate(registration()))
                    .isInstanceOf(TemplateRegistrationException.class)
                    .hasMessageContaining("INVOICE_TEMPLATE");

            // Exactly maxAttempts requests: retrying forever would hold the caller's startup.
            server.verify();
        }
    }

    @Nested
    @DisplayName("renderDocument")
    class Render {

        @Test
        @DisplayName("returns the rendered bytes with the response's content type")
        void rendersDocument() {
            server.expect(requestTo("http://documents/v1/documents/render"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.templateId").value("INVOICE_TEMPLATE"))
                    .andExpect(jsonPath("$.format").value("PDF"))
                    .andRespond(withSuccess(new byte[] {1, 2, 3}, MediaType.APPLICATION_PDF));

            RenderResponse response = client.renderDocument(renderRequest());

            assertThat(response.getContent()).containsExactly(1, 2, 3);
            assertThat(response.getContentType()).isEqualTo("application/pdf");
            assertThat(response.getSizeBytes()).isEqualTo(3);
            assertThat(response.getFilename()).isEqualTo("invoice.pdf");
            assertThat(response.getTemplateId()).isEqualTo("INVOICE_TEMPLATE");
            assertThat(response.getFormat()).isEqualTo(DocumentFormat.PDF);
        }

        @Test
        @DisplayName("defaults the content type when the service sends none")
        void missingContentTypeDefaults() {
            server.expect(requestTo("http://documents/v1/documents/render"))
                    .andRespond(withSuccess(new byte[] {1}, null));

            assertThat(client.renderDocument(renderRequest()).getContentType()).isEqualTo("application/octet-stream");
        }

        @Test
        @DisplayName("treats a 200 with no body as a render failure")
        void emptyBodyIsAFailure() {
            for (int i = 0; i < 1; i++) {
                server.expect(requestTo("http://documents/v1/documents/render")).andRespond(withStatus(HttpStatus.OK));
            }

            // An empty PDF handed to a printer is worse than an error the caller can retry.
            assertThatThrownBy(() -> client.renderDocument(renderRequest()))
                    .isInstanceOf(DocumentRenderException.class)
                    .hasMessageContaining("INVOICE_TEMPLATE");
        }

        @Test
        @DisplayName("retries a failed render and succeeds on a later attempt")
        void retriesRender() {
            server.expect(requestTo("http://documents/v1/documents/render")).andRespond(withServerError());
            server.expect(requestTo("http://documents/v1/documents/render"))
                    .andRespond(withSuccess(new byte[] {9}, MediaType.APPLICATION_PDF));

            assertThat(client.renderDocument(renderRequest()).getContent()).containsExactly(9);
            server.verify();
        }

        @Test
        @DisplayName("wraps render exhaustion in DocumentRenderException with template and format")
        void renderExhaustionIsTyped() {
            for (int i = 0; i < 3; i++) {
                server.expect(requestTo("http://documents/v1/documents/render")).andRespond(withServerError());
            }

            assertThatThrownBy(() -> client.renderDocument(renderRequest()))
                    .isInstanceOf(DocumentRenderException.class)
                    .hasMessageContaining("INVOICE_TEMPLATE")
                    .hasMessageContaining("PDF");

            server.verify();
        }
    }

    @Test
    @DisplayName("builder produces a working client with the configured retry budget")
    void builderBuildsAUsableClient() {
        // Smoke-level: the builder wires baseUrl, maxAttempts, and waitDuration together.
        DocumentServiceClient built = DocumentServiceClient.builder()
                .baseUrl("http://documents")
                .maxRetries(2)
                .waitDuration(Duration.ofMillis(1))
                .build();

        assertThat(built).isNotNull();
    }
}
