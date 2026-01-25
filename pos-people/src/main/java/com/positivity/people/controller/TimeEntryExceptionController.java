package com.positivity.people.controller;

import com.positivity.people.dto.TimeEntryExceptionRequest;
import com.positivity.people.dto.TimeEntryExceptionResponse;
import com.positivity.people.entity.TimeEntryException;
import com.positivity.people.repository.TimeEntryExceptionRepository;
import com.positivity.people.service.TimeEntryExceptionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@RestController
@RequestMapping("/v1/people/exceptions")
@Tag(name = "People - Exceptions", description = "Time entry exception APIs")
public class TimeEntryExceptionController {

    private final TimeEntryExceptionRepository exceptionRepository;
    private final TimeEntryExceptionService exceptionService;

    public TimeEntryExceptionController(TimeEntryExceptionRepository exceptionRepository,
            TimeEntryExceptionService exceptionService) {
        this.exceptionRepository = exceptionRepository;
        this.exceptionService = exceptionService;
    }

    @Operation(summary = "Create a time entry exception")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Exception created"),
            @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<TimeEntryExceptionResponse> createException(@RequestBody TimeEntryExceptionRequest req) {
        TimeEntryException e = new TimeEntryException();
        e.setEmployeeId(req.getEmployeeId());
        e.setExceptionCode(req.getExceptionCode());
        if (req.getSeverity() != null) {
            try {
                e.setSeverity(com.positivity.people.model.ExceptionSeverity.valueOf(req.getSeverity()));
            } catch (IllegalArgumentException iae) {
                e.setSeverity(com.positivity.people.model.ExceptionSeverity.WARNING);
            }
        }
        e.setTimeEntryId(req.getTimeEntryId());
        e.setResolutionNotes(req.getResolutionNotes());
        if (req.getDetectedAt() != null) {
            e.setDetectedAt(req.getDetectedAt().toInstant());
        } else {
            e.setDetectedAt(java.time.Instant.now());
        }
        e.setStatus(com.positivity.people.model.ExceptionStatus.OPEN);

        TimeEntryException saved = exceptionRepository.save(e);
        TimeEntryExceptionResponse resp = new TimeEntryExceptionResponse(saved.getExceptionId(), true, "created");
        return ResponseEntity.ok(resp);
    }

    @Operation(summary = "List exceptions, optional filter by employeeId")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List returned")
    })
    @GetMapping(produces = "application/json")
    public ResponseEntity<List<TimeEntryException>> listByEmployee(@RequestParam(required = false) String employeeId) {
        List<TimeEntryException> list;
        if (employeeId == null) {
            list = exceptionRepository.findAll();
        } else {
            list = exceptionRepository.findByEmployeeId(employeeId);
        }
        return ResponseEntity.ok(list);
    }

    @Operation(summary = "Resolve an exception")
    @PostMapping(value = "/{exceptionId}/resolve", produces = "application/json")
    public ResponseEntity<?> resolveException(@PathVariable java.util.UUID exceptionId,
            @RequestParam(required = false) String action,
            @RequestBody(required = false) java.util.Map<String, String> body,
            @RequestHeader(value = "X-Permissions", required = false) String permissionsHeader,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        java.util.Set<String> perms = null;
        if (permissionsHeader != null && !permissionsHeader.isBlank()) {
            perms = java.util.Arrays.stream(permissionsHeader.split(",")).map(String::trim)
                    .collect(java.util.stream.Collectors.toSet());
        }
        String actor = userId != null ? userId : "system";
        String notes = null;
        if (body != null && body.containsKey("resolutionNotes"))
            notes = body.get("resolutionNotes");
        String resolutionAction = action != null ? action : "RESOLVED";

        boolean ok = exceptionService.resolveException(exceptionId, actor, perms, notes, resolutionAction,
                correlationId);
        if (ok)
            return ResponseEntity.ok().build();
        com.positivity.people.dto.ErrorResponse err = new com.positivity.people.dto.ErrorResponse("FORBIDDEN",
                "forbidden or not found", correlationId);
        return ResponseEntity.status(403).body(err);
    }
}
