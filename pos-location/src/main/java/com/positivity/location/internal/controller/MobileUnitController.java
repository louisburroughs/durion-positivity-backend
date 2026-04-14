package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.CoverageRuleResponse;
import com.positivity.location.internal.dto.MobileUnitRequest;
import com.positivity.location.internal.dto.MobileUnitResponse;
import com.positivity.location.service.MobileUnitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Tag(name = "Mobile Unit API", description = "Operations for managing mobile units within locations")
@RestController
@RequestMapping("/v1/mobile-units")
@RequiredArgsConstructor
public class MobileUnitController {

    private final MobileUnitService mobileUnitService;

    @Operation(summary = "Create mobile unit", description = "Create a new mobile unit.")
    @ApiResponse(responseCode = "201", description = "Mobile unit created successfully.")
    @EmitEvent(id = "LOCATION_MOBILE_UNIT_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('location:mobile-unit:manage')")
    @PostMapping
    public ResponseEntity<MobileUnitResponse> createMobileUnit(
            @Parameter(description = "Mobile unit creation request body") @RequestBody MobileUnitRequest request) {
        log.info("Creating mobile unit with name(mask)={}", maskForLog(request != null ? request.getName() : null));
        return ResponseEntity.status(HttpStatus.CREATED).body(mobileUnitService.createMobileUnit(request));
    }

    @Operation(summary = "List mobile units", description = "List mobile units with pagination.")
    @ApiResponse(responseCode = "200", description = "Mobile units retrieved successfully.")
    @PreAuthorize("hasAuthority('location:mobile-unit:read')")
    @GetMapping
    public ResponseEntity<Page<MobileUnitResponse>> listMobileUnits(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(mobileUnitService.list(page, size));
    }

    @Operation(summary = "Get mobile unit", description = "Get a mobile unit by ID.")
    @ApiResponse(responseCode = "200", description = "Mobile unit returned.")
    @ApiResponse(responseCode = "404", description = "Mobile unit not found.")
    @PreAuthorize("hasAuthority('location:mobile-unit:read')")
    @GetMapping("/{id}")
    public ResponseEntity<MobileUnitResponse> getMobileUnitById(
            @Parameter(description = "Mobile unit ID") @PathVariable UUID id) {
        return mobileUnitService
                .getById(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mobile unit not found"));
    }

    @Operation(summary = "Patch mobile unit", description = "Partially update a mobile unit.")
    @ApiResponse(responseCode = "200", description = "Mobile units managed successfully.")
    @EmitEvent(id = "LOCATION_MOBILE_UNIT_UPDATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('location:mobile-unit:manage')")
    @PatchMapping("/{id}")
    public ResponseEntity<MobileUnitResponse> patchMobileUnit(
            @PathVariable UUID id, @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(mobileUnitService.patch(id, patch));
    }

    @Operation(summary = "Replace coverage rules", description = "Atomically replace coverage rules for a mobile unit.")
    @ApiResponse(responseCode = "200", description = "Coverage rules replaced successfully.")
    @ApiResponse(responseCode = "404", description = "Mobile unit not found.")
    @EmitEvent(id = "LOCATION_COVERAGE_RULES_REPLACE", apiVersion = "1")
    @PreAuthorize("hasAuthority('location:mobile-unit:manage')")
    @PutMapping("/{id}/coverage-rules")
    public ResponseEntity<List<CoverageRuleResponse>> replaceCoverageRules(
            @PathVariable UUID id, @RequestBody Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawRules = (List<Map<String, Object>>) payload.getOrDefault("rules", List.of());
        return ResponseEntity.ok(mobileUnitService.replaceCoverageRules(id.toString(), rawRules));
    }

    @Operation(summary = "Get coverage rules", description = "Get coverage rules for a mobile unit.")
    @ApiResponse(responseCode = "200", description = "Coverage rules returned.")
    @PreAuthorize("hasAuthority('location:mobile-unit:read')")
    @GetMapping("/{id}/coverage-rules")
    public ResponseEntity<List<CoverageRuleResponse>> getCoverageRules(@PathVariable UUID id) {
        return ResponseEntity.ok(mobileUnitService.getCoverageRules(id));
    }

    private String maskForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String sanitized =
                value.toString().replace('\r', '_').replace('\n', '_').replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
    }
}
