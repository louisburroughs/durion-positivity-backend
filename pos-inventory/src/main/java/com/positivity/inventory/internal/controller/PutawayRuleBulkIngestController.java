package com.positivity.inventory.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.putaway.PutawayRuleBulkIngestRecord;
import com.positivity.inventory.internal.dto.putaway.PutawayRuleRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayRuleResponse;
import com.positivity.inventory.internal.exception.DuplicateEnabledAnyPutawayRuleException;
import com.positivity.inventory.internal.putaway.service.PutawayRuleService;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creates putaway rules in bulk.
 *
 * <p>At most one enabled ANY rule may exist, and the service enforces that both ahead of the write
 * and with a database constraint. Here that is treated as "already configured" rather than as a
 * failure, so re-running a rule set converges — but it is reported with the existing rule's id,
 * because an ANY rule pointing somewhere other than the file intended is the difference between
 * every unclassified line landing in general storage and every one of them being refused.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"inventory:putaway_rule:manage"})
@RequestMapping("/v1/inventory/putaway")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Putaway Rule Bulk Ingest API", description = "Bulk import putaway routing rules")
public class PutawayRuleBulkIngestController extends AbstractBulkIngestController<PutawayRuleBulkIngestRecord> {

    private static final String INGEST_FAILED = "PUTAWAY_RULE_INGEST_FAILED";

    private static final String BULK_INGEST_EXAMPLE = """
            {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a00",
             "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
             "operatorId":"seed-operator",
             "records":[
               {"priority":10,"matchType":"CATEGORY","matchValue":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a20",
                "destinationLocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01","destinationStrategy":"FIXED"},
               {"priority":100,"matchType":"ANY",
                "destinationLocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02","destinationStrategy":"FIXED"}
             ]}
            """;

    private final PutawayRuleService putawayRuleService;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.PUTAWAY_RULE_MANAGE + "')")
    @EmitEvent(id = "INVENTORY_PUTAWAY_RULE_BULK_INGEST", apiVersion = "1")
    @Operation(operationId = "bulkIngestPutawayRules", summary = "Create Putaway Rules in Bulk", description = """
                    Creates many putaway rules at once, one per record.
                    Use this tool when configuring a site's routing or seeding an environment; use createPutawayRule \
                    instead for a single rule.
                    Preconditions: every tier except ANY must carry a matchValue, ANY must not, and an enabled rule's \
                    destination must exist in the storage-location replica unless that replica is still empty.
                    Required inputs: jobId (UUID), locationId (UUID) and records, each with a priority, a matchType \
                    and a destinationLocationId.
                    Emits an INVENTORY_PUTAWAY_RULE_BULK_INGEST event and a rule-created event per row.
                    At most one enabled ANY rule may exist; a second is reported as already configured, carrying the \
                    existing rule's id so it can be checked — an ANY rule pointing somewhere unintended refuses every \
                    unclassified line rather than catching it.
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
                            description = "Putaway rules to create.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Category rule and terminal fallback",
                                                            value = BULK_INGEST_EXAMPLE)))
                    @Valid
                    @RequestBody
                    BulkIngestRequest<PutawayRuleBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<PutawayRuleBulkIngestRecord> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < request.getRecords().size(); i++) {
            PutawayRuleBulkIngestRecord record = request.getRecords().get(i);
            try {
                PutawayRuleResponse created = putawayRuleService.createRule(toRuleRequest(record));
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(parseUuidOrNull(created.getRuleId()))
                        .success(true)
                        .build());
                successCount++;
            } catch (DuplicateEnabledAnyPutawayRuleException exception) {
                log.warn(
                        "Row {}: an enabled ANY putaway rule already exists ({}); check where it points",
                        i,
                        exception.getExistingRuleId());
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(exception.getExistingRuleId())
                        .success(true)
                        .errorCode(DuplicateEnabledAnyPutawayRuleException.ERROR_CODE)
                        .errorMessage("An enabled ANY rule already exists; this row was not applied")
                        .build());
                successCount++;
            } catch (Exception exception) {
                log.warn("Failed to ingest putaway rule at row {}: {}", i, exception.getMessage());
                String message = exception.getMessage();
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .success(false)
                        .errorCode(INGEST_FAILED)
                        .errorMessage(message == null || message.isBlank() ? "Putaway rule ingest failed" : message)
                        .build());
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

    private PutawayRuleRequest toRuleRequest(PutawayRuleBulkIngestRecord record) {
        PutawayRuleRequest ruleRequest = new PutawayRuleRequest();
        ruleRequest.setPriority(record.getPriority());
        ruleRequest.setMatchType(record.getMatchType());
        ruleRequest.setMatchValue(
                record.getMatchValue() == null ? null : record.getMatchValue().toString());
        ruleRequest.setDestinationLocationId(record.getDestinationLocationId());
        ruleRequest.setDestinationStrategy(record.getDestinationStrategy());
        ruleRequest.setIsEnabled(record.getIsEnabled());
        return ruleRequest;
    }

    private UUID parseUuidOrNull(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
