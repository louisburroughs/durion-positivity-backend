package com.positivity.referencemock.internal.controller;

import com.positivity.referencemock.internal.dto.FeedChunkDto;
import com.positivity.referencemock.internal.dto.FeedManifestDto;
import com.positivity.referencemock.internal.dto.ProviderLaborTimeDto;
import com.positivity.referencemock.internal.dto.ProviderOperationDto;
import com.positivity.referencemock.internal.dto.VehicleQuery;
import com.positivity.referencemock.internal.service.ChaosService;
import com.positivity.referencemock.internal.service.LaborGuideService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Durion-normalized labor-guide provider contract, v1 (service-time-sourcing-plan §10).
 *
 * <p>This controller plays an external vendor, so it deliberately breaks platform conventions:
 * no {@code @PreAuthorize} (the mock sits outside the gateway/JWT boundary), no
 * {@code @EmitEvent} (vendors are outside our event mesh), no {@code X-API-Version} rewrite
 * (the version lives in the path, as vendor APIs do). Every endpoint accepts the chaos knobs
 * {@code delayMs} (sleep before responding, capped at 10000 ms) and {@code failRate}
 * (probability of a bodiless 503) so consumers can rehearse vendor degradation.
 */
@Tag(
        name = "Labor Guide Provider Contract (Mock)",
        description = "Durion-normalized labor-guide vendor contract served from deterministic checked-in"
                + " fixtures. Live lookups (operations, labor-times) back the QUERY_ONLY provider mode;"
                + " the feed manifest/chunk pair backs the STORE-mode chunked-manifest import. All endpoints"
                + " accept chaos knobs delayMs (capped 10000) and failRate (0.0-1.0, bodiless 503) for"
                + " degradation testing.")
@RestController
@RequiredArgsConstructor
@RequestMapping("/mock/labor-guide/v1")
public class LaborGuideMockController {

    private static final String CHAOS_503 = "Injected by the failRate chaos knob; the response has NO body."
            + " Consumers must treat this exactly like a real vendor outage (typed degradation, never a 500).";

    private final LaborGuideService laborGuideService;
    private final ChaosService chaosService;

    /**
     * Lists vendor operations applicable to a vehicle.
     *
     * @param year model year, wildcard when absent
     * @param make vehicle make, wildcard when absent
     * @param model vehicle model, wildcard when absent
     * @param submodel vehicle submodel, wildcard when absent
     * @param engineCode engine code, wildcard when absent
     * @param search case-insensitive substring on the operation name
     * @param delayMs chaos: sleep before responding, capped at 10000 ms
     * @param failRate chaos: probability of a bodiless 503
     * @return operations whose applicability rows match the vehicle
     */
    @GetMapping("/operations")
    @Operation(
            operationId = "findMockLaborGuideOperations",
            summary = "List vendor operations applicable to a vehicle",
            description = """
                    Lists the vendor operations whose fixture applicability rows match the given vehicle \
                    fields, optionally narrowed by a case-insensitive substring search on the operation name. \
                    Use this tool when discovering which providerOperationCode values exist for a vehicle \
                    before resolving times; do not use getMockLaborTime for discovery, it resolves exactly \
                    one already-known code. Preconditions: none, the fixture catalog is loaded at startup and \
                    never changes. Required inputs: none are mandatory; absent vehicle parameters act as \
                    wildcards, and search filters on the operation name only. No events are emitted; this is \
                    a deterministic read over checked-in fixtures. Returns 200 with a possibly empty JSON \
                    array, and 503 with no body only when the failRate chaos knob triggers.""")
    @ApiResponse(responseCode = "200", description = "Operations applicable to the vehicle, possibly empty")
    @ApiResponse(responseCode = "503", description = CHAOS_503, content = @Content)
    public ResponseEntity<List<ProviderOperationDto>> findOperations(
            @Parameter(description = "Model year, e.g. 2019; wildcard when absent") @RequestParam(required = false)
                    String year,
            @Parameter(description = "Vehicle make, e.g. Honda; wildcard when absent") @RequestParam(required = false)
                    String make,
            @Parameter(description = "Vehicle model, e.g. Civic; wildcard when absent") @RequestParam(required = false)
                    String model,
            @Parameter(description = "Vehicle submodel/trim, e.g. EX; wildcard when absent")
                    @RequestParam(required = false)
                    String submodel,
            @Parameter(description = "Engine code, e.g. L15B7; wildcard when absent") @RequestParam(required = false)
                    String engineCode,
            @Parameter(description = "Case-insensitive substring on the operation name") @RequestParam(required = false)
                    String search,
            @Parameter(description = "Chaos: delay before responding in ms, capped at 10000")
                    @RequestParam(required = false)
                    Long delayMs,
            @Parameter(description = "Chaos: probability [0.0-1.0] of a bodiless 503") @RequestParam(required = false)
                    Double failRate) {
        chaosService.delay(delayMs);
        if (chaosService.shouldFail(failRate)) {
            return ResponseEntity.status(503).build();
        }
        return ResponseEntity.ok(
                laborGuideService.findOperations(new VehicleQuery(year, make, model, submodel, engineCode), search));
    }

    /**
     * Resolves the most specific labor time for (vehicle, provider operation).
     *
     * @param providerOperationCode the vendor operation code to resolve
     * @param year model year
     * @param make vehicle make
     * @param model vehicle model
     * @param submodel vehicle submodel
     * @param engineCode engine code
     * @param delayMs chaos: sleep before responding, capped at 10000 ms
     * @param failRate chaos: probability of a bodiless 503
     * @return the winning labor time, or 404 with no body when nothing matches
     */
    @GetMapping("/labor-times")
    @Operation(
            operationId = "getMockLaborTime",
            summary = "Resolve the most specific labor time for a vehicle and operation",
            description = """
                    Resolves one published labor time for the given providerOperationCode and vehicle, picking \
                    the most specific matching fixture row: rows naming more vehicle fields beat rows leaving \
                    them null-wildcarded, and a row's non-null field only matches when the request supplies an \
                    equal value case-insensitively. Use this tool when a consumer needs the QUERY_ONLY live \
                    answer for one operation on one vehicle; do not use findMockLaborGuideOperations for \
                    times, it returns applicability only. Preconditions: the code should exist in the fixture \
                    catalog, discoverable via findMockLaborGuideOperations. Required inputs: \
                    providerOperationCode; vehicle fields are optional but a fully keyed request resolves the \
                    most specific row (equal-specificity ties resolve RETAIL_FLAT_RATE before OEM_WARRANTY \
                    before MANUFACTURER_INSTALL). No events are emitted; this is a deterministic read over \
                    checked-in fixtures. Returns 200 with the winning time, 404 with no body when no fixture \
                    row matches, and 503 with no body only when the failRate chaos knob triggers.""")
    @ApiResponse(responseCode = "200", description = "The most specific matching labor time")
    @ApiResponse(
            responseCode = "404",
            description = "No fixture row matches the operation and vehicle; the response has NO body.",
            content = @Content)
    @ApiResponse(responseCode = "503", description = CHAOS_503, content = @Content)
    public ResponseEntity<ProviderLaborTimeDto> getLaborTime(
            @Parameter(description = "Vendor operation code, e.g. MG-BRAKE-PAD-FRONT", required = true) @RequestParam
                    String providerOperationCode,
            @Parameter(description = "Model year, e.g. 2019") @RequestParam(required = false) String year,
            @Parameter(description = "Vehicle make, e.g. Honda") @RequestParam(required = false) String make,
            @Parameter(description = "Vehicle model, e.g. Civic") @RequestParam(required = false) String model,
            @Parameter(description = "Vehicle submodel/trim, e.g. EX") @RequestParam(required = false) String submodel,
            @Parameter(description = "Engine code, e.g. L15B7") @RequestParam(required = false) String engineCode,
            @Parameter(description = "Chaos: delay before responding in ms, capped at 10000")
                    @RequestParam(required = false)
                    Long delayMs,
            @Parameter(description = "Chaos: probability [0.0-1.0] of a bodiless 503") @RequestParam(required = false)
                    Double failRate) {
        chaosService.delay(delayMs);
        if (chaosService.shouldFail(failRate)) {
            return ResponseEntity.status(503).build();
        }
        return laborGuideService
                .findLaborTime(providerOperationCode, new VehicleQuery(year, make, model, submodel, engineCode))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Returns the feed manifest for the current fixture revision.
     *
     * @param sinceRevision the consumer's last imported revision; informational only
     * @param delayMs chaos: sleep before responding, capped at 10000 ms
     * @param failRate chaos: probability of a bodiless 503
     * @return the manifest of the current revision
     */
    @GetMapping("/feed/manifest")
    @Operation(
            operationId = "getMockLaborGuideFeedManifest",
            summary = "Get the feed manifest for the current revision",
            description = """
                    Returns the STORE-mode import manifest for the current fixture revision: a per-revision \
                    fixed importManifestId, the sourceRevision, expected chunk and line counts, and a SHA-256 \
                    hex contentChecksum over the canonical concatenation of all lines (per line the fields \
                    providerOperationCode, vehicleYear, make, model, submodel, engineCode, hours, timeType, \
                    overlapGroup, comma-joined includedOperations and publishedAt joined with '|', nulls as \
                    empty strings, lines joined with newline). Use this tool when starting a chunked-manifest \
                    import; do not use getMockLaborGuideFeedChunk before holding a manifest, chunks are \
                    validated against its id. Preconditions: none. Required inputs: none; sinceRevision is \
                    informational and the manifest is returned even when it equals the current revision, the \
                    caller decides whether to skip. No events are emitted; the manifest is deterministic and \
                    byte-identical across calls. Returns 200 always, and 503 with no body only when the \
                    failRate chaos knob triggers.""")
    @ApiResponse(responseCode = "200", description = "The manifest of the current fixture revision")
    @ApiResponse(responseCode = "503", description = CHAOS_503, content = @Content)
    public ResponseEntity<FeedManifestDto> getManifest(
            @Parameter(description = "The consumer's last imported revision; informational only")
                    @RequestParam(required = false)
                    String sinceRevision,
            @Parameter(description = "Chaos: delay before responding in ms, capped at 10000")
                    @RequestParam(required = false)
                    Long delayMs,
            @Parameter(description = "Chaos: probability [0.0-1.0] of a bodiless 503") @RequestParam(required = false)
                    Double failRate) {
        chaosService.delay(delayMs);
        if (chaosService.shouldFail(failRate)) {
            return ResponseEntity.status(503).build();
        }
        return ResponseEntity.ok(laborGuideService.manifest());
    }

    /**
     * Returns one 50-line chunk of the feed.
     *
     * @param seq 1-based chunk sequence
     * @param manifestId the manifest being imported
     * @param delayMs chaos: sleep before responding, capped at 10000 ms
     * @param failRate chaos: probability of a bodiless 503
     * @return the chunk, or 404 for an unknown sequence or mismatched manifest id
     */
    @GetMapping("/feed/chunks/{seq}")
    @Operation(
            operationId = "getMockLaborGuideFeedChunk",
            summary = "Get one 50-line chunk of the labor-time feed",
            description = """
                    Returns one chunk of the labor-time feed, at most 50 lines, addressed by 1-based sequence \
                    number and validated against the manifest id the caller is importing. Use this tool when \
                    pulling a STORE-mode import after getMockLaborGuideFeedManifest; do not use getMockLaborTime \
                    for bulk loading, it resolves one time per call. Preconditions: a manifest obtained from \
                    getMockLaborGuideFeedManifest, whose importManifestId must accompany every chunk request. \
                    Required inputs: seq between 1 and expectedChunkCount, and manifestId equal to the current \
                    manifest's id. No events are emitted; chunk content and ordering are deterministic, so \
                    re-fetching a chunk is always safe. Returns 200 with the chunk, 404 with no body for an \
                    unknown sequence or a mismatched manifestId, and 503 with no body only when the failRate \
                    chaos knob triggers.""")
    @ApiResponse(responseCode = "200", description = "The requested chunk")
    @ApiResponse(
            responseCode = "404",
            description = "Unknown chunk sequence or mismatched manifestId; the response has NO body.",
            content = @Content)
    @ApiResponse(responseCode = "503", description = CHAOS_503, content = @Content)
    public ResponseEntity<FeedChunkDto> getChunk(
            @Parameter(description = "1-based chunk sequence") @PathVariable int seq,
            @Parameter(description = "Manifest id from getMockLaborGuideFeedManifest", required = true) @RequestParam
                    UUID manifestId,
            @Parameter(description = "Chaos: delay before responding in ms, capped at 10000")
                    @RequestParam(required = false)
                    Long delayMs,
            @Parameter(description = "Chaos: probability [0.0-1.0] of a bodiless 503") @RequestParam(required = false)
                    Double failRate) {
        chaosService.delay(delayMs);
        if (chaosService.shouldFail(failRate)) {
            return ResponseEntity.status(503).build();
        }
        return laborGuideService
                .chunk(seq, manifestId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
