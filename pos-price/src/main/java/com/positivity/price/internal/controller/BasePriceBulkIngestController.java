package com.positivity.price.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.price.internal.dto.BasePriceBulkIngestRecord;
import com.positivity.price.internal.exception.BasePriceWindowConflictException;
import com.positivity.price.internal.security.PricingPermissions;
import com.positivity.price.internal.service.BasePriceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"pricing:base_price:create"})
@RequestMapping("/v1/price")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('" + PricingPermissions.BASE_PRICE_CREATE + "')")
@Tag(name = "Price Bulk Ingest API", description = "Bulk import base price records")
public class BasePriceBulkIngestController extends AbstractBulkIngestController<BasePriceBulkIngestRecord> {

    private final BasePriceService basePriceService;

    private static final String BULK_INGEST_EXAMPLE = """
            {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4d01",
             "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4d02",
             "operatorId":"user-jdoe",
             "records":[
               {"productId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b01",
                "msrp":"125.50",
                "currency":"USD",
                "effectiveFrom":"2026-03-01T00:00:00Z"}]}
            """;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasAuthority('" + PricingPermissions.BASE_PRICE_CREATE + "')")
    @EmitEvent(id = "PRICE_BULK_INGEST", apiVersion = "1")
    @Operation(operationId = "bulkIngestBasePrices", summary = "Bulk Import Base Price Records", description = """
                    Bulk-imports base price (MSRP) records, appending an effective-dated price window per product and \
                    currency.
                    Use this tool for batch price loads from an upstream catalog; use calculatePriceQuote instead to \
                    read the price effective at a pricing instant, since quoting resolves these windows.
                    Preconditions: each record's effectiveFrom must start after the product's current window begins; \
                    re-submitting the current open window's unchanged price is idempotent and returns the existing id.
                    Required inputs: jobId and locationId (UUIDs) and records (at least one), each record carrying \
                    productId (UUID string), msrp (decimal string), currency (ISO 4217 code), and effectiveFrom as an \
                    ISO-8601 instant such as 2026-03-01T00:00:00Z; operatorId is optional.
                    Emits a PRICE_BULK_INGEST event; a new window closes the previous open window at its effectiveFrom \
                    and rows are processed independently, so one bad row does not abort the batch.
                    Returns 200 with per-row results even when rows fail, marking a refused row with errorCode \
                    PRICE_INGEST_FAILED and the reason (overlapping-window conflicts and unparseable productId, msrp \
                    or effectiveFrom values), and a row lost to a server-side fault with INTERNAL_ERROR and a \
                    correlationId to quote, with no detail of its own. Returns 400 when the batch envelope itself is \
                    invalid.
                    """)
    @ApiResponse(responseCode = "200", description = "Batch processed; check per-record success and failure results.")
    @ApiResponse(responseCode = "400", description = "Invalid batch envelope.")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Batch envelope of base-price records to ingest, scoped to a job and location.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Single price window",
                                                            value = BULK_INGEST_EXAMPLE)))
                    @Valid
                    @RequestBody
                    @NonNull
                    BulkIngestRequest<BasePriceBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<BasePriceBulkIngestRecord> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < request.getRecords().size(); i++) {
            BasePriceBulkIngestRecord ingestRecord = request.getRecords().get(i);
            try {
                UUID productId = parseProductId(ingestRecord.getProductId());
                BigDecimal msrp = parseMsrp(ingestRecord.getMsrp());
                Instant effectiveFrom = parseEffectiveFrom(ingestRecord.getEffectiveFrom());
                UUID savedProductId =
                        basePriceService.saveBasePrice(productId, msrp, ingestRecord.getCurrency(), effectiveFrom);
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(savedProductId)
                        .success(true)
                        .build());
                successCount++;
            } catch (Exception exception) {
                results.add(rowFailure(i, exception));
                failureCount++;
            }
        }

        return BulkIngestResponse.builder()
                .totalSubmitted(request.getRecords().size())
                .successCount(successCount)
                .failureCount(failureCount)
                .results(results)
                .build();
    }

    /**
     * A row this endpoint refused: the three text fields it has to parse itself, and the window
     * collision {@link BasePriceService#saveBasePrice} raises. Everything else — a Hibernate
     * error, a malformed stored value — is a server-side fault reported generically against a
     * correlation id (issue #1718).
     */
    @Override
    protected Collection<Class<? extends Throwable>> rowRejectionTypes() {
        return List.of(UnparseableFieldException.class, BasePriceWindowConflictException.class);
    }

    @Override
    protected String rowRejectionCode() {
        return "PRICE_INGEST_FAILED";
    }

    @Override
    protected String rowRejectionFallbackMessage() {
        return "Price ingest failed";
    }

    private static UUID parseProductId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException _) {
            throw new UnparseableFieldException("productId is not a valid UUID");
        }
    }

    private static BigDecimal parseMsrp(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException _) {
            throw new UnparseableFieldException("msrp is not a valid decimal number");
        }
    }

    private static Instant parseEffectiveFrom(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException _) {
            throw new UnparseableFieldException("effectiveFrom is not a valid ISO-8601 instant");
        }
    }

    /**
     * Raised for a field this controller could not parse. The record carries msrp and
     * effectiveFrom as text, so the parse is part of accepting the row and its failure is the
     * caller\'s to fix — but the JDK types it raises ({@code IllegalArgumentException} and
     * friends) are exactly the ones a Hibernate or JPA-converter fault also arrives as, and are
     * therefore not classifiable as a rejection. Naming the failure at the point where it is
     * still unambiguous keeps the row\'s message useful without widening what is echoed
     * (issue #1718).
     */
    static final class UnparseableFieldException extends RuntimeException {
        UnparseableFieldException(String message) {
            super(message);
        }
    }
}
