package com.positivity.shopmanager.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.shopmanager.internal.dto.MechanicSkillBulkIngestRecord;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import com.positivity.shopmanager.internal.security.ShopPermissions;
import com.positivity.shopmanager.internal.service.MechanicSyncService;
import com.positivity.shopmanager.internal.service.dto.HrMechanicEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Sets mechanics' skills in bulk.
 *
 * <p>Rows arrive one per skill, and are folded by person before being applied: the underlying
 * operation replaces a mechanic's whole skill set, so applying rows one at a time would leave each
 * mechanic holding only their last skill.
 *
 * <p>Every row of a mechanic shares that mechanic's outcome, because they were applied as one call.
 * A mechanic whose projection has not caught up yet fails rather than silently doing nothing — the
 * mechanic rows are projected from staffing assignments over Kafka, so this pack can genuinely run
 * before its subjects exist, and that is a "try again shortly", not a bad file.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"shop:schedule:edit"})
@RequestMapping("/v1/shop-manager/mechanics")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Mechanic Skill Bulk Ingest API", description = "Bulk set mechanics' skill sets")
public class MechanicSkillBulkIngestController extends AbstractBulkIngestController<MechanicSkillBulkIngestRecord> {

    private static final String INGEST_FAILED = "MECHANIC_SKILL_INGEST_FAILED";

    private static final String BULK_INGEST_EXAMPLE = """
            {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a00",
             "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
             "operatorId":"seed-operator",
             "records":[
               {"personId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b","skillCode":"T4-BRAKES","proficiencyLevel":4},
               {"personId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b","skillCode":"T3-ALIGN","proficiencyLevel":3}
             ]}
            """;

    private final MechanicSyncService mechanicSyncService;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasAuthority('" + ShopPermissions.SCHEDULE_EDIT + "')")
    @EmitEvent(id = "SHOP_MECHANIC_SKILLS_BULK_INGEST", apiVersion = "1")
    @Operation(
            operationId = "bulkIngestMechanicSkills",
            summary = "Set Mechanics' Skill Sets in Bulk",
            description = """
                    Sets many mechanics' skill sets at once, folding the rows of each mechanic into a single \
                    replacement.
                    Use this tool when seeding a workshop's capabilities; use replaceMechanicSkills instead for a \
                    single mechanic.
                    Preconditions: each person must already exist as a mechanic. Mechanics are projected from \
                    ACTIVE TECHNICIAN staffing assignments over Kafka, so run this after those assignments and \
                    allow the projection to catch up.
                    Required inputs: jobId (UUID), locationId (UUID) and records, each with a personId, a \
                    skillCode and a proficiencyLevel from 1 to 5. A mechanic appears once per skill; the rows are \
                    grouped here.
                    Emits a SHOP_MECHANIC_SKILLS_BULK_INGEST event, and routes each mechanic's set through the \
                    same HR-feed path the Kafka projection uses, so dedupe, stale-guard and audit apply as usual; \
                    re-running the same file is safe, since each mechanic's set is replaced rather than added to.
                    Returns 200 with a per-record result, where every row of one mechanic shares that mechanic's \
                    outcome since they were applied together: MECHANIC_SKILL_INGEST_FAILED with the reason for rows \
                    the service refused, or INTERNAL_ERROR with a correlationId to quote for rows lost to a \
                    server-side fault.
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
                            description = "Mechanic skills, one row per skill.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Skills", value = BULK_INGEST_EXAMPLE)))
                    @Valid
                    @RequestBody
                    BulkIngestRequest<MechanicSkillBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<MechanicSkillBulkIngestRecord> request) {

        // Grouped in file order so a mechanic's skills are applied in the order they were written,
        // and so the results can be reported against the rows they came from.
        Map<String, List<Integer>> rowsByPerson = new LinkedHashMap<>();
        Map<String, List<HrMechanicEvent.Payload.Skill>> skillsByPerson = new LinkedHashMap<>();
        for (int i = 0; i < request.getRecords().size(); i++) {
            MechanicSkillBulkIngestRecord record = request.getRecords().get(i);
            rowsByPerson
                    .computeIfAbsent(record.personId(), _ -> new ArrayList<>())
                    .add(i);
            skillsByPerson
                    .computeIfAbsent(record.personId(), _ -> new ArrayList<>())
                    .add(HrMechanicEvent.Payload.Skill.builder()
                            .skillCode(record.skillCode())
                            .proficiencyLevel(record.proficiencyLevel())
                            .build());
        }

        BulkIngestResult[] results = new BulkIngestResult[request.getRecords().size()];
        int successCount = 0;
        int failureCount = 0;

        for (Map.Entry<String, List<Integer>> entry : rowsByPerson.entrySet()) {
            String personId = entry.getKey();
            List<Integer> rowIndexes = entry.getValue();
            try {
                mechanicSyncService.replaceSkills(personId, skillsByPerson.get(personId));
                for (int rowIndex : rowIndexes) {
                    results[rowIndex] = BulkIngestResult.builder()
                            .rowIndex(rowIndex)
                            .entityId(parseUuidOrNull(personId))
                            .success(true)
                            .build();
                }
                successCount += rowIndexes.size();
            } catch (Exception exception) {
                // One call covers every row for this mechanic, so the failure is classified and
                // logged once and the outcome it produced is reported against each of those rows.
                // The rows are named here as well, because rowFailure's own entry can only carry
                // the one it was given: without this an operator quoting a correlation id from
                // row 7 would find a log line naming row 3.
                log.warn("Skills for mechanic {} failed for rows {}", personId, rowIndexes);
                BulkIngestResult classified = rowFailure(rowIndexes.getFirst(), exception);
                for (int rowIndex : rowIndexes) {
                    results[rowIndex] = BulkIngestResult.builder()
                            .rowIndex(rowIndex)
                            .success(false)
                            .errorCode(classified.getErrorCode())
                            .errorMessage(classified.getErrorMessage())
                            // Copied too: on a server fault this id is the whole of what the
                            // caller can act on, and dropping it from every row but the first
                            // would leave most of them with nothing to quote.
                            .correlationId(classified.getCorrelationId())
                            .build();
                }
                failureCount += rowIndexes.size();
            }
        }

        return BulkIngestResponse.builder()
                .totalSubmitted(request.getRecords().size())
                .successCount(successCount)
                .failureCount(failureCount)
                .results(List.of(results))
                .build();
    }

    private UUID parseUuidOrNull(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    /**
     * The one thing {@link MechanicSyncService#replaceSkills} refuses about the rows themselves:
     * a skill code or proficiency the shop does not recognise, which
     * {@link com.positivity.shopmanager.internal.controller.GlobalExceptionHandler} answers 400.
     * Everything else — an unsupported operation, an unreachable sibling service, a persistence
     * fault — is ours, and is reported generically against a correlation id (issue #1718).
     */
    @Override
    protected Collection<Class<? extends Throwable>> rowRejectionTypes() {
        return List.of(ShopManagerValidationException.class);
    }

    @Override
    protected String rowRejectionCode() {
        return INGEST_FAILED;
    }

    @Override
    protected String rowRejectionFallbackMessage() {
        return "Mechanic skill ingest failed";
    }
}
