package com.positivity.inventory.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.cyclecount.service.CycleCountPlanService;
import com.positivity.inventory.internal.dto.cyclecount.plan.CreateCycleCountPlanRequest;
import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountPlanBulkIngestRecord;
import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountPlanResponse;
import com.positivity.inventory.internal.exception.CycleCountPlanNotFoundException;
import com.positivity.inventory.internal.exception.InventoryValidationException;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creates cycle count plans in bulk.
 *
 * <p>Two things a caller cannot easily get right on its own are handled here. A plan's scheduled
 * date must be strictly in the future, so a record may say how many days out it wants rather than
 * naming a date that will be in the past the next time the file runs. And nothing makes plan names
 * unique, so an existing plan with the same name at the same site is recognised and left alone
 * instead of silently duplicated on every re-run.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"inventory:cycle_count:initiate"})
@RequestMapping("/v1/inventory/cycleCountPlans")
@RequiredArgsConstructor
@Tag(name = "Cycle Count Plan Bulk Ingest API", description = "Bulk import cycle count plans")
public class CycleCountPlanBulkIngestController extends AbstractBulkIngestController<CycleCountPlanBulkIngestRecord> {

    private static final String INGEST_FAILED = "CYCLE_COUNT_PLAN_INGEST_FAILED";
    private static final int DEFAULT_DAYS_OUT = 30;
    private static final int EXISTING_PLAN_PAGE_SIZE = 200;

    private static final String BULK_INGEST_EXAMPLE = """
            {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a00",
             "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
             "operatorId":"seed-operator",
             "records":[
               {"planName":"Q1 Fast Movers","scheduledDaysOut":30,
                "zoneIds":["018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01","018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02"]}
             ]}
            """;

    private final CycleCountPlanService cycleCountPlanService;
    private final Clock clock;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.CYCLE_COUNT_INITIATE + "')")
    @EmitEvent(id = "INVENTORY_CYCLE_COUNT_PLAN_BULK_INGEST", apiVersion = "1")
    @Operation(
            operationId = "bulkIngestCycleCountPlans",
            summary = "Create Cycle Count Plans in Bulk",
            description = """
                    Creates many cycle count plans at once, one per record, each in PLANNED status.
                    Use this tool when standing up a counting programme or seeding an environment; use \
                    createCycleCountPlan instead for a single plan, and generateCycleCountTasks to turn a plan \
                    into work.
                    Preconditions: each site must exist and each row must name at least one zone.
                    Required inputs: jobId (UUID), locationId (UUID) and records, each with a planName and zoneIds; \
                    give either a scheduledDate strictly in the future or a scheduledDaysOut, which is counted from \
                    today so a replayed file never asks for a past date.
                    Emits an INVENTORY_CYCLE_COUNT_PLAN_BULK_INGEST event and a plan-created event per row.
                    Re-running the same file is safe: a plan whose name is already present at that site is \
                    recognised rather than duplicated, since nothing in the schema makes plan names unique.
                    Returns 200 with a per-record result; check each result rather than the status alone.
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
                            description = "Cycle count plans to create.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Quarterly plan",
                                                            value = BULK_INGEST_EXAMPLE)))
                    @Valid
                    @RequestBody
                    BulkIngestRequest<CycleCountPlanBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<CycleCountPlanBulkIngestRecord> request) {

        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;
        String createdBy = resolveActor(request);
        Map<UUID, Map<String, UUID>> existingBySite = new HashMap<>();

        for (int i = 0; i < request.getRecords().size(); i++) {
            CycleCountPlanBulkIngestRecord record = request.getRecords().get(i);
            UUID locationId = record.getLocationId() == null ? request.getLocationId() : record.getLocationId();
            try {
                Map<String, UUID> existing = existingBySite.computeIfAbsent(locationId, this::existingPlanNames);
                UUID alreadyThere = existing.get(record.getPlanName());
                if (alreadyThere != null) {
                    results.add(BulkIngestResult.builder()
                            .rowIndex(i)
                            .entityId(alreadyThere)
                            .success(true)
                            .build());
                    successCount++;
                    continue;
                }

                CycleCountPlanResponse created =
                        cycleCountPlanService.createPlan(toPlanRequest(record, locationId), createdBy);
                existing.put(record.getPlanName(), created.getPlanId());
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(created.getPlanId())
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

    private CreateCycleCountPlanRequest toPlanRequest(CycleCountPlanBulkIngestRecord record, UUID locationId) {
        return CreateCycleCountPlanRequest.builder()
                .locationId(locationId)
                .planName(record.getPlanName())
                .zoneIds(record.getZoneIds())
                .scheduledDate(scheduledDate(record))
                .build();
    }

    /**
     * The date to schedule for. A record naming one is taken at its word — the service still refuses
     * anything not strictly in the future. Otherwise it is counted from today, which is what lets a
     * checked-in file stay loadable however long after it was written it runs.
     */
    private LocalDate scheduledDate(CycleCountPlanBulkIngestRecord record) {
        if (record.getScheduledDate() != null) {
            return record.getScheduledDate();
        }
        int daysOut = record.getScheduledDaysOut() == null ? DEFAULT_DAYS_OUT : record.getScheduledDaysOut();
        return LocalDate.now(clock).plusDays(daysOut);
    }

    private Map<String, UUID> existingPlanNames(UUID locationId) {
        Map<String, UUID> byName = new HashMap<>();
        List<CycleCountPlanResponse> plans =
                cycleCountPlanService.listPlans(locationId, null, 0, EXISTING_PLAN_PAGE_SIZE);
        for (CycleCountPlanResponse plan : plans) {
            byName.put(plan.getPlanName(), plan.getPlanId());
        }
        return byName;
    }

    private String resolveActor(BulkIngestRequest<CycleCountPlanBulkIngestRecord> request) {
        return Optional.ofNullable(request.getOperatorId())
                .filter(operatorId -> !operatorId.isBlank())
                .orElse("system");
    }

    /**
     * What {@link CycleCountPlanService#createPlan} refuses about the record itself — a missing
     * locationId, an empty zone list, a scheduled date that is not in the future — plus a plan
     * naming a site that does not exist. Everything else is a server-side fault, reported
     * generically against a correlation id (issue #1718).
     */
    @Override
    protected Collection<Class<? extends Throwable>> rowRejectionTypes() {
        return List.of(InventoryValidationException.class, CycleCountPlanNotFoundException.class);
    }

    @Override
    protected String rowRejectionCode() {
        return INGEST_FAILED;
    }

    @Override
    protected String rowRejectionFallbackMessage() {
        return "Cycle count plan ingest failed";
    }
}
