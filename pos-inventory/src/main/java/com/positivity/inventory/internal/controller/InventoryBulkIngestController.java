package com.positivity.inventory.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.CreateAdjustmentRequestDto;
import com.positivity.inventory.internal.dto.InventoryBulkIngestRecord;
import com.positivity.inventory.internal.movement.service.StockMovementService;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"inventory:adjustment:create"})
@RequestMapping("/v1/inventory")
@RequiredArgsConstructor
@Tag(name = "Inventory Bulk Ingest API", description = "Bulk import inventory adjustment requests")
public class InventoryBulkIngestController extends AbstractBulkIngestController<InventoryBulkIngestRecord> {

    private static final String DEFAULT_REASON_CODE = "CYCLE_COUNT_ADJUSTMENT";
    private final StockMovementService stockMovementService;

    @Override
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.ADJUSTMENT_CREATE + "')")
    @EmitEvent(id = "INVENTORY_BULK_INGEST", apiVersion = "1")
    @Operation(
            operationId = "bulkIngestInventoryAdjustments",
            summary = "Bulk ingest inventory adjustments",
            description = """
                    Processes a batch of inventory adjustment records, creating one PENDING adjustment request per \
                    accepted row for the normal approval flow rather than posting stock changes directly.
                    Use this tool for bulk imports such as cycle-count loads; do not use createAdjustmentRequest, \
                    which files a single interactive adjustment, and do not expect on-hand to change until each \
                    request is approved.
                    Preconditions: none beyond authentication; rows are processed independently, so one bad row \
                    fails only itself.
                    Required inputs: jobId (UUID), locationId (UUID) and at least one record with sku and a \
                    non-negative quantity; a record-level locationId overrides the batch location, reasonCode \
                    defaults to CYCLE_COUNT_ADJUSTMENT, and operatorId attributes the rows when a service account \
                    submits the batch.
                    Emits an INVENTORY_BULK_INGEST event; each accepted row persists a PENDING adjustment request \
                    attributed to the resolved actor.
                    Returns 200 with per-row results — a row the service refused carries errorCode \
                    INVENTORY_INGEST_FAILED and the reason, while a row lost to a server-side fault carries \
                    INGEST_INTERNAL_ERROR and an errorMessage holding only a correlationId to quote — and 400 when \
                    the envelope itself is invalid because jobId, locationId or records are missing.
                    """,
            tags = {"Inventory Bulk Ingest API"})
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Batch envelope with the job, target location and the adjustment records"
                                    + " to file.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Cycle-count batch", value = """
                                                                    {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a20",
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
                                                                     "operatorId":"user-jdoe",
                                                                     "records":[{"sku":"SKU-10042","quantity":120,
                                                                                 "reasonCode":"CYCLE_COUNT",
                                                                                 "unitOfMeasure":"EACH"}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    BulkIngestRequest<InventoryBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<InventoryBulkIngestRecord> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;
        String actorUserId = resolveActorUserId(request);

        for (int i = 0; i < request.getRecords().size(); i++) {
            InventoryBulkIngestRecord ingestRecord = request.getRecords().get(i);
            try {
                UUID locationId =
                        ingestRecord.getLocationId() == null ? request.getLocationId() : ingestRecord.getLocationId();
                CreateAdjustmentRequestDto adjustmentRequest = CreateAdjustmentRequestDto.builder()
                        .productSku(ingestRecord.getSku())
                        .locationId(locationId)
                        .quantity(ingestRecord.getQuantity())
                        .reasonCode(firstNonBlank(ingestRecord.getReasonCode(), DEFAULT_REASON_CODE))
                        .unitOfMeasure(ingestRecord.getUnitOfMeasure())
                        .build();

                var created = stockMovementService.createAdjustmentRequest(adjustmentRequest, actorUserId);
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(created.getAdjustmentRequestId())
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

    private String resolveActorUserId(@NonNull BulkIngestRequest<InventoryBulkIngestRecord> request) {
        // Prefer request.operatorId when provided so bulk-loader service-account calls
        // correctly attribute to the human operator that initiated the import.
        if (request.getOperatorId() != null && !request.getOperatorId().isBlank()) {
            return request.getOperatorId();
        }
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getName() != null && !auth.getName().isBlank()) {
            return auth.getName();
        }
        return "system";
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    /**
     * No rejection types: {@code StockMovementService#createAdjustmentRequest} validates nothing
     * and refuses nothing — it builds the request and saves it — so a failure on this path is a
     * persistence fault, not the caller\'s row, and belongs behind a correlation id
     * (issue #1718). Bare {@code IllegalArgumentException} is deliberately not named even though
     * this module\'s advice answers it 400: it is equally what Hibernate and the JDK raise, and
     * its message is the kind this fix exists to stop echoing.
     */
    @Override
    protected String rowRejectionCode() {
        return "INVENTORY_INGEST_FAILED";
    }

    @Override
    protected String rowRejectionFallbackMessage() {
        return "Inventory ingest failed";
    }
}
