package com.positivity.invoice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.jpa.hibernate.ddl-auto=update",
            "spring.datasource.url=jdbc:h2:mem:invoice-openapi;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.flyway.enabled=false",
            "eureka.client.enabled=false",
            "spring.boot.admin.client.enabled=false",
            "logging.level.root=WARN",
            "logging.level.org.hibernate.SQL=ERROR"
        })
class InvoiceOpenApiContractTest {

    @LocalServerPort
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("openApiSpec_invoiceReceiptAndPaymentEndpoints_haveSummaryAndDescription")
    @SuppressWarnings("unchecked")
    void openApiSpec_invoiceReceiptAndPaymentEndpoints_haveSummaryAndDescription() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/v3/api-docs"))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());

        Map<String, Object> openApiSpec = objectMapper.readValue(response.body(), Map.class);
        Map<String, Object> paths = (Map<String, Object>) openApiSpec.get("paths");

        assertOperationSummaryAndDescription(paths, "/v1/invoices/{invoiceId}/receipts", "post");
        assertOperationSummaryAndDescription(paths, "/v1/invoices/{invoiceId}/receipts/{receiptId}/reprint", "post");
        assertOperationSummaryAndDescription(paths, "/v1/invoices/{invoiceId}/receipts/{receiptId}/print", "post");
        assertOperationSummaryAndDescription(paths, "/v1/invoices/{invoiceId}/receipts/{receiptId}/email", "post");
        assertOperationDescription(paths, "/v1/invoices/{invoiceId}/payments/{paymentId}/capture", "post");
        assertOperationDescription(paths, "/v1/invoices/{invoiceId}/payments/{paymentId}/void", "post");
        assertOperationDescription(paths, "/v1/invoices/{invoiceId}/payments/{paymentId}/refunds", "post");
    }

    @SuppressWarnings("unchecked")
    private static void assertOperationSummaryAndDescription(Map<String, Object> paths, String path, String method) {
        Map<String, Object> pathItem = (Map<String, Object>) paths.get(path);
        assertThat(pathItem).as("path %s should exist", path).isNotNull();

        Map<String, Object> operation = (Map<String, Object>) pathItem.get(method);
        assertThat(operation)
                .as("%s %s operation should exist", method.toUpperCase(), path)
                .isNotNull();
        assertThat(operation.get("summary"))
                .as("%s %s should have a non-empty summary", method.toUpperCase(), path)
                .isInstanceOf(String.class)
                .asString()
                .isNotBlank();
        assertThat(operation.get("description"))
                .as("%s %s should have a non-empty description", method.toUpperCase(), path)
                .isInstanceOf(String.class)
                .asString()
                .isNotBlank();
    }

    @SuppressWarnings("unchecked")
    private static void assertOperationDescription(Map<String, Object> paths, String path, String method) {
        Map<String, Object> pathItem = (Map<String, Object>) paths.get(path);
        assertThat(pathItem).as("path %s should exist", path).isNotNull();

        Map<String, Object> operation = (Map<String, Object>) pathItem.get(method);
        assertThat(operation)
                .as("%s %s operation should exist", method.toUpperCase(), path)
                .isNotNull();
        assertThat(operation.get("description"))
                .as("%s %s should have a non-empty description", method.toUpperCase(), path)
                .isInstanceOf(String.class)
                .asString()
                .isNotBlank();
    }
}
