package com.positivity.order.internal.client;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls {@code POST /v1/supplier/stock/inquiries} on pos-supplier (CAP-319 #1329).
 *
 * <h2>This file is the grant</h2>
 *
 * {@code DomainWallsTest} allows the synchronous edge to pos-supplier by file name, not by module.
 * Any other client class in pos-order that reaches pos-supplier fails the build, which is the point:
 * the exception exists for live availability specifically, and a second caller has to make its own
 * case rather than inheriting this one.
 *
 * <h2>Nothing here rethrows</h2>
 *
 * A buyer is on the other end of this call, deciding what to order. A supplier that is down, slow,
 * or misconfigured must degrade the availability column, never fail the screen — so a transport
 * failure becomes {@code SUPPLIER_UNAVAILABLE} rather than an exception. That is a status the
 * caller can render honestly as "we could not ask", which is different from "the vendor has none"
 * and must stay different all the way to the buyer.
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
    @NonNull
    public StockAnswer inquire(
            @NonNull UUID vendorProfileId, @NonNull UUID deliveryLocationId, @NonNull List<InquiryLine> lines) {
        if (lines.isEmpty()) {
            // Nothing to ask about. Asking anyway would spend a vendor round trip to learn nothing.
            return new StockAnswer(Status.OK, List.of(), null);
        }

        Map<String, Object> body = Map.of(
                "inquiryId",
                UUID.randomUUID().toString(),
                "vendorProfileId",
                vendorProfileId.toString(),
                "deliveryLocationId",
                deliveryLocationId.toString(),
                "lines",
                lines.stream().map(SupplierStockClientImpl::toRequestLine).toList());

        try {
            InquiryResponse response = restClient
                    .post()
                    .uri(basePath + "/stock/inquiries")
                    .body(body)
                    .retrieve()
                    .body(InquiryResponse.class);
            if (response == null) {
                log.warn("Supplier stock inquiry to {} returned no body", vendorProfileId);
                return unavailable();
            }
            return toAnswer(response);
        } catch (Exception e) {
            // Deliberately broad, and deliberately not rethrown. Whatever went wrong — refused
            // connection, timeout, 5xx, unparseable body — the honest answer to the buyer is the
            // same: we could not ask. Letting this escape would take down a procurement screen
            // because a vendor's endpoint was having a bad afternoon.
            log.warn("Supplier stock inquiry to {} failed; reporting unavailable", vendorProfileId, e);
            return unavailable();
        }
    }

    private static StockAnswer unavailable() {
        return new StockAnswer(Status.SUPPLIER_UNAVAILABLE, List.of(), null);
    }

    private static Map<String, Object> toRequestLine(InquiryLine line) {
        Map<String, Object> mapped = new java.util.HashMap<>();
        mapped.put("articleEan", line.articleEan());
        mapped.put("supplierArticleCode", line.supplierArticleCode());
        mapped.put("requestedQuantity", line.requestedQuantity());
        return mapped;
    }

    private static StockAnswer toAnswer(InquiryResponse response) {
        Status status = parseStatus(response.status());
        List<AnswerLine> lines = new ArrayList<>();
        if (response.lines() != null) {
            for (ResponseLine line : response.lines()) {
                lines.add(new AnswerLine(
                        line.articleEan(),
                        line.supplierArticleCode(),
                        parseLineStatus(line.status()),
                        // Carried through exactly as received. Substituting zero for a null here
                        // would tell a buyer the vendor has none when it said nothing at all.
                        line.availableQuantity(),
                        line.earliestDeliveryDate()));
            }
        }
        return new StockAnswer(status, lines, response.asOf());
    }

    /** An unrecognised status is treated as no answer, never as a usable one. */
    private static Status parseStatus(String raw) {
        if (raw == null) {
            return Status.SUPPLIER_UNAVAILABLE;
        }
        try {
            return Status.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.warn("Unrecognised supplier stock inquiry status '{}'; treating as unavailable", raw);
            return Status.SUPPLIER_UNAVAILABLE;
        }
    }

    /** An unrecognised line status becomes "said nothing", which is the safe reading. */
    private static LineStatus parseLineStatus(String raw) {
        if (raw == null) {
            return LineStatus.NOT_ANSWERED;
        }
        try {
            return LineStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.warn("Unrecognised supplier stock line status '{}'; treating as not answered", raw);
            return LineStatus.NOT_ANSWERED;
        }
    }

    /** Wire shape of the supplier response; deliberately separate from the domain-facing model. */
    private record InquiryResponse(UUID inquiryId, String status, List<ResponseLine> lines, Instant asOf) {}

    private record ResponseLine(
            String articleEan,
            String supplierArticleCode,
            String status,
            Integer availableQuantity,
            LocalDate earliestDeliveryDate) {}
}
