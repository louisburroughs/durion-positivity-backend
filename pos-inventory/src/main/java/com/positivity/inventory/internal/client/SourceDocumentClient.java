package com.positivity.inventory.internal.client;

import com.positivity.inventory.internal.enums.SourceDocumentType;
import com.positivity.inventory.internal.exception.SourceDocumentNotFoundException;
import com.positivity.inventory.internal.exception.SourceDocumentServiceUnavailableException;
import com.positivity.inventory.internal.exception.UnsupportedSourceDocumentTypeException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.Data;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads a receiving session's source document from the service that owns it (issue #1480).
 *
 * <p>Replaces {@code SourceDocumentStubClient}, which was gated behind
 * {@code pos.inventory.receiving.stub.enabled} — false by default, which returned no lines and
 * made every {@code POST /v1/inventory/receiving/sessions} answer {@code 404}. Enabling it pointed
 * at {@code /stub/v1/source-documents/...}, a path no service serves. Everything built on a
 * session (staging, cross-dock, session reads) was unreachable as a result.
 *
 * <p>The one source document type the receiving flow is specified against is the purchase order,
 * and pos-order already serves it: {@code GET /v1/orders/purchase-orders/{poId}} returns the order
 * with its lines and their open quantities. That is what this client calls — a real endpoint, on
 * the same load-balanced service-to-service path pos-inventory uses elsewhere, with no flag in
 * front of it.
 *
 * <p>Expected quantity is the line's <em>open</em> quantity when pos-order reports one: a
 * partially received PO must not re-expect what has already arrived. A line with nothing open is
 * dropped, and a document whose lines are all closed is reported as already received rather than
 * as an empty session.
 *
 * <h2>ASN</h2>
 *
 * ASN is not a supported source document type. The retired stub resolved it to a
 * {@code pos-shipments} service that does not exist, so it could only ever have produced the same
 * misleading {@code 404} as an unknown PO. It is rejected explicitly instead — see
 * {@link UnsupportedSourceDocumentTypeException}.
 */
@Component
public class SourceDocumentClient {

    private static final Logger log = LoggerFactory.getLogger(SourceDocumentClient.class);

    /** Statuses on which a purchase order has nothing left to receive. */
    private static final List<String> CLOSED_STATUSES = List.of("RECEIVED", "COMPLETED", "CLOSED", "CANCELLED");

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient orderRestClient;

    public SourceDocumentClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.order.service-id:order}") String orderServiceId) {
        this.orderRestClient =
                restClientBuilder.baseUrl("http://" + orderServiceId).build();
    }

    /**
     * Fetches the receivable lines of {@code sourceDocumentId}.
     *
     * @throws UnsupportedSourceDocumentTypeException when the type is not {@link SourceDocumentType#PO}
     * @throws SourceDocumentNotFoundException when the owning service has no such document
     * @throws SourceDocumentServiceUnavailableException when the owning service could not be reached
     */
    @NonNull
    public SourceDocument fetchDocument(
            @NonNull SourceDocumentType sourceDocumentType, @NonNull String sourceDocumentId) {
        if (sourceDocumentType != SourceDocumentType.PO) {
            throw new UnsupportedSourceDocumentTypeException(sourceDocumentType.name());
        }
        return fetchPurchaseOrder(sourceDocumentId);
    }

    private SourceDocument fetchPurchaseOrder(String poId) {
        Map<String, Object> body;
        try {
            body = orderRestClient
                    .get()
                    .uri("/v1/orders/purchase-orders/{poId}", poId)
                    .header("X-User", "pos-inventory")
                    .header("X-Authorities", "order:purchase_order:view")
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        throw new SourceDocumentNotFoundException(
                                "No purchase order " + poId + " in pos-order (responded "
                                        + response.getStatusCode().value() + ")");
                    })
                    .body(MAP_TYPE);
        } catch (SourceDocumentNotFoundException e) {
            throw e;
        } catch (RestClientException e) {
            // A transport failure is not "the document does not exist": answering 404 here is what
            // made the stub's silence indistinguishable from a genuinely unknown PO (#1480).
            throw new SourceDocumentServiceUnavailableException(
                    "pos-order could not be reached for purchase order " + poId, e);
        }

        if (body == null) {
            throw new SourceDocumentNotFoundException("pos-order returned no body for purchase order " + poId);
        }

        String status = asString(body.get("status"));
        List<SourceDocumentLine> lines = mapLines(body.get("lines"));
        boolean closed = isClosedStatus(status);
        if (!closed && lines.isEmpty() && hasAnyLine(body.get("lines"))) {
            // Every line is fully received even though the header has not been closed out.
            closed = true;
        }

        log.debug(
                "Resolved purchase order {} from pos-order: status={} receivableLines={}", poId, status, lines.size());
        return new SourceDocument(sourceDocumentId(poId), status, closed, lines);
    }

    private static String sourceDocumentId(String poId) {
        return poId;
    }

    @SuppressWarnings("unchecked")
    private List<SourceDocumentLine> mapLines(@Nullable Object rawLines) {
        if (!(rawLines instanceof List<?> rows)) {
            return List.of();
        }
        List<SourceDocumentLine> lines = new ArrayList<>(rows.size());
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> line = (Map<String, Object>) map;
            BigDecimal expected = receivableQuantity(line);
            if (expected == null || expected.signum() <= 0) {
                continue;
            }
            SourceDocumentLine mapped = new SourceDocumentLine();
            mapped.setSourceLineId(asString(line.get("lineId")));
            mapped.setProductId(asString(line.get("skuId")));
            mapped.setExpectedQuantity(expected);
            lines.add(mapped);
        }
        return lines;
    }

    /**
     * What is still expected on this line: the open quantity when pos-order reports one, else the
     * ordered quantity. {@code openQuantityDecimal} is absent on an order that has never been
     * received against, and zero once a line is complete.
     */
    private static @Nullable BigDecimal receivableQuantity(Map<String, Object> line) {
        BigDecimal open = asDecimal(line.get("openQuantityDecimal"));
        return open != null ? open : asDecimal(line.get("quantityDecimal"));
    }

    private static boolean hasAnyLine(@Nullable Object rawLines) {
        return rawLines instanceof List<?> rows && !rows.isEmpty();
    }

    private static boolean isClosedStatus(@Nullable String status) {
        return status != null && CLOSED_STATUSES.contains(status.trim().toUpperCase(Locale.ROOT));
    }

    private static @Nullable String asString(@Nullable Object value) {
        return value == null ? null : value.toString();
    }

    private static @Nullable BigDecimal asDecimal(@Nullable Object value) {
        return switch (value) {
            case null -> null;
            case BigDecimal decimal -> decimal;
            case Number number -> new BigDecimal(number.toString());
            case String text -> parseDecimal(text);
            default -> null;
        };
    }

    private static @Nullable BigDecimal parseDecimal(String text) {
        try {
            return text.isBlank() ? null : new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * A source document as receiving needs it: its identifier, the owner's status string, whether
     * there is anything left to receive, and the lines that still expect goods.
     */
    public record SourceDocument(
            @NonNull String sourceDocumentId,
            @Nullable String status,
            boolean alreadyReceived,
            @NonNull List<SourceDocumentLine> lines) {

        public SourceDocument {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /**
     * One receivable line of a source document.
     *
     * <p>{@code expectedQuantity} is in the product's base unit, matching what a receiving line
     * compares received quantities against. The document unit the purchase order was keyed in is
     * deliberately not carried here: it belongs to the ordered quantity, not to this still-open
     * base quantity, and pairing the two would label a base number with a document unit. Receiving
     * takes its document unit from the receive request instead (odoo-parity B2, #1034).
     */
    @Data
    public static class SourceDocumentLine {
        private String sourceLineId;
        private String productId;
        private BigDecimal expectedQuantity;
    }
}
