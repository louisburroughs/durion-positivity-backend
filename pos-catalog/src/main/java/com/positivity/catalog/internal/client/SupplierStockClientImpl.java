package com.positivity.catalog.internal.client;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * RestClient implementation of {@link SupplierStockClient}: {@code POST
 * /v1/supplier/stock/inquiries} on the supplier service.
 *
 * <h2>Every failure is an empty answer</h2>
 *
 * A customer is waiting on the page this feeds. Nothing here rethrows: a connection refused, a
 * timeout, a 4xx, a body that will not deserialise, and the supplier service reporting its own
 * degraded status all become {@link Optional#empty()}, which the composition renders as "we could not
 * find out" rather than as no stock. The supplier service is already built never to fail for a
 * vendor-side problem, so an exception reaching this catch means something structural is wrong on
 * our side — logged at warn, and still not allowed to take a product page down.
 *
 * <h2>Disabled unless a profile is configured</h2>
 *
 * A deployment with no supplier integration sets no {@code pos.supplier.stock.vendor-profile-id} and
 * this client is never asked anything, so the component simply does not appear. That is the default:
 * live vendor stock is an opt-in for deployments that have a trading partner, not something every
 * installation inherits.
 */
@Slf4j
@Component
public class SupplierStockClientImpl implements SupplierStockClient {

    private final RestClient restClient;
    private final String basePath;

    public SupplierStockClientImpl(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.supplier.service-id:supplier}") String serviceId,
            @Value("${pos.supplier.base-path:/v1/supplier}") String basePath) {
        this.basePath = basePath;
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
    }

    @Override
    public Optional<StockInquiryClientResponse> inquireStock(
            UUID vendorProfileId, UUID deliveryLocationId, String articleEan, String supplierArticleCode) {

        if (vendorProfileId == null || deliveryLocationId == null) {
            return Optional.empty();
        }
        if (isBlank(articleEan) && isBlank(supplierArticleCode)) {
            // Nothing to ask about. Calling anyway would spend a vendor round trip to be told what
            // we already know.
            return Optional.empty();
        }

        InquiryRequest body = new InquiryRequest(
                UUID.randomUUID(),
                vendorProfileId,
                deliveryLocationId,
                List.of(new InquiryRequestLine(emptyToNull(articleEan), emptyToNull(supplierArticleCode), 1)));

        try {
            StockInquiryClientResponse response = restClient
                    .post()
                    .uri(basePath + "/stock/inquiries")
                    .header("X-User", "pos-catalog-service")
                    .header("X-Authorities", "supplier:stock:inquire")
                    .body(body)
                    .retrieve()
                    .body(StockInquiryClientResponse.class);

            if (response == null || !"OK".equals(response.status())) {
                log.debug(
                        "Supplier stock inquiry did not answer for profile {}: status={}",
                        vendorProfileId,
                        response == null ? "no body" : response.status());
                return Optional.empty();
            }
            return Optional.of(response);
        } catch (Exception e) {
            // The supplier service reports vendor trouble as a status, not an exception, so an
            // exception here is ours: unreachable service, wrong path, undeserialisable body. Still
            // not a reason to fail a product page.
            log.warn("Supplier stock inquiry failed for profile {}: {}", vendorProfileId, e.toString());
            return Optional.empty();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String emptyToNull(String value) {
        return isBlank(value) ? null : value;
    }

    // -----------------------------------------------------------------------
    // Internal request shape (mirrors the supplier service API contract)
    // -----------------------------------------------------------------------

    private record InquiryRequest(
            UUID inquiryId, UUID vendorProfileId, UUID deliveryLocationId, List<InquiryRequestLine> lines) {}

    private record InquiryRequestLine(String articleEan, String supplierArticleCode, int requestedQuantity) {}
}
