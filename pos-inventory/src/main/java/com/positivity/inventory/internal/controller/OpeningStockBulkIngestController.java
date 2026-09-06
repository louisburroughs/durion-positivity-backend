package com.positivity.inventory.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.AvailabilityView;
import com.positivity.inventory.internal.dto.CreateAdjustmentRequestDto;
import com.positivity.inventory.internal.dto.OpeningStockBulkIngestRecord;
import com.positivity.inventory.internal.movement.service.StockMovementService;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.inventory.internal.service.InventoryAvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Establishes opening on-hand stock in bulk.
 *
 * <p>Distinct from {@link InventoryBulkIngestController}, which files adjustment requests and
 * leaves them PENDING for a human to approve. Opening balances have no one to review them — the
 * stock is being declared, not corrected — so this files and approves in one step, which is what
 * actually posts the ledger entry and creates the on-hand the replicas then see. That is why it
 * demands the approval permission as well as the create permission: it performs both acts.
 *
 * <p>Adjustments are deltas, so running the same file twice would double the stock. A line whose
 * product already has on-hand at its destination is therefore skipped rather than posted again, and
 * reported as a success with no entity id — the desired state already holds, which is what the
 * caller asked for.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"inventory:adjustment:create", "inventory:adjustment:approve"})
@RequestMapping("/v1/inventory/opening-stock")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Inventory Opening Stock API", description = "Bulk establish opening on-hand quantities")
public class OpeningStockBulkIngestController extends AbstractBulkIngestController<OpeningStockBulkIngestRecord> {

    private static final String DEFAULT_REASON_CODE = "OPENING_BALANCE";
    private static final String INGEST_FAILED = "OPENING_STOCK_INGEST_FAILED";

    private static final String BULK_INGEST_EXAMPLE = """
            {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a00",
             "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
             "operatorId":"seed-operator",
             "records":[
               {"sku":"MOBI-120764","locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01","quantity":24,"unitOfMeasure":"EA"},
               {"sku":"WIXF-51394","locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02","quantity":12,"unitOfMeasure":"EA"}
             ]}
            """;

    private final StockMovementService stockMovementService;
    private final InventoryAvailabilityService availabilityService;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.ADJUSTMENT_CREATE + "') and hasAuthority('"
            + InventoryPermissionRegistry.ADJUSTMENT_APPROVE + "')")
    @EmitEvent(id = "INVENTORY_OPENING_STOCK_BULK_INGEST", apiVersion = "1")
    @Operation(
            operationId = "bulkIngestOpeningStock",
            summary = "Establish Opening On-Hand Stock in Bulk",
            description = """
                    Establishes opening on-hand quantities for many products at once, filing an adjustment per line \
                    and approving it so the ledger entry posts and the stock actually exists.
                    Use this tool when commissioning a site or seeding an environment; use bulkIngestInventoryAdjustments \
                    instead to file adjustments that a human must review and approve.
                    Preconditions: the caller holds both inventory:adjustment:create and inventory:adjustment:approve, \
                    and each line's storage location exists.
                    Required inputs: jobId (UUID), locationId (UUID) and records, each with sku and a positive quantity; \
                    a record's own locationId names the storage location the stock sits in and overrides the batch one.
                    Emits an INVENTORY_OPENING_STOCK_BULK_INGEST event, and one ADJUSTMENT_IN ledger entry per line, \
                    which is what hydrates the availability and replica views.
                    Re-running the same file is safe: a line whose product already has on-hand at its destination is \
                    skipped, because adjustments are deltas and posting again would double the stock.
                    Returns 200 with a per-record result; check each result rather than the status alone. A row the service refused carries errorCode OPENING_STOCK_INGEST_FAILED and the reason; a row lost to a \
                    server-side fault carries INTERNAL_ERROR and a correlationId to quote, with no detail of its own.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Batch processed; inspect per-record results",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BulkIngestResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request payload")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Opening stock lines to establish.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Opening stock batch",
                                                            value = BULK_INGEST_EXAMPLE)))
                    @Valid
                    @RequestBody
                    BulkIngestRequest<OpeningStockBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<OpeningStockBulkIngestRecord> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;
        String actorUserId = resolveActorUserId(request);

        for (int i = 0; i < request.getRecords().size(); i++) {
            OpeningStockBulkIngestRecord record = request.getRecords().get(i);
            UUID locationId = record.getLocationId() == null ? request.getLocationId() : record.getLocationId();
            try {
                if (alreadyStocked(record.getSku(), locationId)) {
                    results.add(
                            BulkIngestResult.builder().rowIndex(i).success(true).build());
                    successCount++;
                    continue;
                }
                results.add(establish(i, record, locationId, actorUserId));
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

    private BulkIngestResult establish(
            int rowIndex, OpeningStockBulkIngestRecord record, UUID locationId, String actorUserId) {

        CreateAdjustmentRequestDto adjustmentRequest = CreateAdjustmentRequestDto.builder()
                .productSku(record.getSku())
                .locationId(locationId)
                .quantity(record.getQuantity())
                .reasonCode(firstNonBlank(record.getReasonCode(), DEFAULT_REASON_CODE))
                .unitOfMeasure(record.getUnitOfMeasure())
                .build();

        var created = stockMovementService.createAdjustmentRequest(adjustmentRequest, actorUserId);
        // The stock does not exist until this posts the ledger entry, so a failure here is a failed
        // row, not a partial success: the caller must not be told the stock is there when it is not.
        stockMovementService.approveAdjustmentRequest(created.getAdjustmentRequestId(), actorUserId);

        return BulkIngestResult.builder()
                .rowIndex(rowIndex)
                .entityId(created.getAdjustmentRequestId())
                .success(true)
                .build();
    }

    /**
     * Whether this product already holds stock at this destination.
     *
     * <p>A product with no stock summary at all reads as "not stocked" rather than as an error —
     * that is the normal state of a SKU being stocked for the first time, which is the whole point
     * of the call.
     */
    private boolean alreadyStocked(String sku, UUID storageLocationId) {
        try {
            AvailabilityView availability = availabilityService.queryAvailability(sku, null, storageLocationId, null);
            BigDecimal onHand = availability.getOnHandQuantity();
            return onHand != null && onHand.compareTo(BigDecimal.ZERO) > 0;
        } catch (RuntimeException exception) {
            log.debug("No availability for sku {} at {}: {}", sku, storageLocationId, exception.getMessage());
            return false;
        }
    }

    private String resolveActorUserId(@NonNull BulkIngestRequest<OpeningStockBulkIngestRecord> request) {
        // Prefer request.operatorId so a bulk-loader service-account call is still attributed to the
        // human operator who started the import.
        if (request.getOperatorId() != null && !request.getOperatorId().isBlank()) {
            return request.getOperatorId();
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getName() != null ? authentication.getName() : "system";
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * No rejection types: this row creates an adjustment request and immediately approves it, and
     * neither call refuses anything about the record — the create only saves, and the approve\'s
     * own guard is an invariant on the request this method just made, so tripping it is a server
     * defect. A ledger posting failure is likewise ours. All of it reports generically against a
     * correlation id (issue #1718).
     */
    @Override
    protected String rowRejectionCode() {
        return INGEST_FAILED;
    }

    @Override
    protected String rowRejectionFallbackMessage() {
        return "Opening stock ingest failed";
    }
}
