package com.positivity.tax.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationRequest.TaxAddress;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxLineItem;
import com.positivity.tax.common.dto.TaxProviderTransactionResult;
import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import com.positivity.tax.internal.config.TaxProperties;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * #985 live-sandbox behavioral contract test: proves against the REAL Avalara AvaTax sandbox
 * that a committable calculation ({@code SalesInvoice}, uncommitted, {@code code == referenceId})
 * persists a document that a later {@code commit(referenceId)} resolves BY CODE — the exact
 * provider behavior the mocked unit tests assume. Also proves the converse premise of #985:
 * this only works because the committable path persists the document (a {@code SalesOrder}
 * estimate would leave nothing for commit to resolve).
 *
 * <p>Gated on sandbox credentials so CI without secrets skips it:
 * {@code AVALARA_SANDBOX_ACCOUNT_ID}, {@code AVALARA_SANDBOX_LICENSE_KEY}, and optionally
 * {@code AVALARA_SANDBOX_COMPANY_CODE} (defaults to {@code DEFAULT}). Runs in the Failsafe
 * {@code verify} phase ({@code *IT} suffix), never in {@code test}.
 */
@EnabledIfEnvironmentVariable(named = "AVALARA_SANDBOX_ACCOUNT_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AVALARA_SANDBOX_LICENSE_KEY", matches = ".+")
class AvalaraSandboxLifecycleIT {

    private static final String SANDBOX_URL = "https://sandbox-rest.avatax.com";

    private AvalaraTaxProvider sandboxProvider() {
        String accountId = System.getenv("AVALARA_SANDBOX_ACCOUNT_ID");
        String licenseKey = System.getenv("AVALARA_SANDBOX_LICENSE_KEY");
        String companyCode = System.getenv().getOrDefault("AVALARA_SANDBOX_COMPANY_CODE", "DEFAULT");

        TaxProperties properties = new TaxProperties();
        properties.setProvider(TaxProperties.Provider.AVALARA);
        properties.getTestMode().setEnabled(false);
        TaxProperties.Avalara avalara = new TaxProperties.Avalara();
        avalara.setBaseUrl(SANDBOX_URL);
        avalara.setAccountId(accountId);
        avalara.setLicenseKey(licenseKey);
        avalara.setCompanyCode(companyCode);
        properties.setAvalara(avalara);

        String basicAuth = "Basic "
                + Base64.getEncoder().encodeToString((accountId + ":" + licenseKey).getBytes(StandardCharsets.UTF_8));
        RestClient restClient = RestClient.builder()
                .baseUrl(SANDBOX_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, basicAuth)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        Retry retry = Retry.of(
                "avalara-sandbox-it", RetryConfig.custom().maxAttempts(1).build());
        return new AvalaraTaxProvider(restClient, retry, properties, Clock.systemUTC());
    }

    private TaxCalculationRequest committableRequest(UUID referenceId) {
        return TaxCalculationRequest.builder()
                .lineItems(List.of(TaxLineItem.builder()
                        .lineItemId("1")
                        .description("Sandbox contract test line")
                        .quantity(BigDecimal.ONE)
                        .unitPrice(new BigDecimal("100.00"))
                        .build()))
                .destinationAddress(TaxAddress.builder()
                        .countryCode("US")
                        .regionCode("CA")
                        .city("Irvine")
                        .postalCode("92617")
                        .line1("2000 Main Street")
                        .build())
                .currencyCode("USD")
                .customerId("SANDBOX-CONTRACT-TEST")
                .referenceId(referenceId)
                .committable(true)
                .build();
    }

    @Test
    @DisplayName("#985: committable SalesInvoice create persists a document that commit() then resolves by code,"
            + " and void() cleans it up")
    void createThenCommitResolvesByCode() {
        AvalaraTaxProvider provider = sandboxProvider();
        UUID referenceId = UUID.randomUUID();

        // 1) Finalization-time committable calculation: persists SalesInvoice, uncommitted.
        TaxCalculationResponse estimate = provider.estimate(committableRequest(referenceId));
        assertThat(estimate.getTotalTax()).isNotNull();
        assertThat(estimate.getExternalTransactionId()).isNotNull();

        // 2) Lifecycle commit resolves the persisted document BY CODE — the #985 contract.
        //    Before #985 (SalesOrder estimate) this call 404'd: nothing was persisted.
        TaxProviderTransactionResult committed = provider.commit(referenceId);
        assertThat(committed.status()).isEqualTo(TaxProviderTransactionStatus.COMMITTED);
        assertThat(committed.externalTransactionId()).isNotNull();

        // 3) Idempotent re-run of the committable calculation must not fail on a duplicate
        //    code (createoradjust adjusts in place) — the retried-finalization scenario.
        TaxCalculationResponse adjusted = provider.estimate(committableRequest(referenceId));
        assertThat(adjusted.getTotalTax()).isNotNull();

        // 4) Cleanup: void the sandbox document (also proves void resolves by the same code).
        TaxProviderTransactionResult voided = provider.voidTransaction(referenceId);
        assertThat(voided.status()).isEqualTo(TaxProviderTransactionStatus.VOIDED);
    }
}
