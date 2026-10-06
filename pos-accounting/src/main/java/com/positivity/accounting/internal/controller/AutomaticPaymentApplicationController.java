package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.AutomaticPaymentApplicationsPage;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.AutomaticPaymentApplicationQueryService;
import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Matched automatically" (#2503; spec §5.1 item 6, §5.3 item 4, §7.1 "Done automatically"): the
 * payment applications nobody made by hand, newest first, each with Undo while it stands.
 */
@Slf4j
@RestController
@RequestMapping("/v1/accounting")
@RequiredArgsConstructor
@Tag(name = "Payment Applications", description = "Manage payment applications to invoices (AR)")
public class AutomaticPaymentApplicationController {

    static final int MAX_PAGE_SIZE = 100;

    /** How far back {@code since} may reach, in days (spec §5.3: "this week", with room for a month). */
    static final int MAX_LOOKBACK_DAYS = 31;

    private final AutomaticPaymentApplicationQueryService queryService;
    private final Clock clock;

    @GetMapping("/payment-applications/automatic")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:payment:apply"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.PAYMENT_APPLY + "')")
    @Operation(
            operationId = "listAutomaticPaymentApplications",
            summary = "List Payment Applications Made Automatically",
            description = """
                    Lists the payment applications made automatically since an instant, newest first: a settled \
                    payment applied to the invoice it was taken against, or an INVOICE_PAYMENT event applied to \
                    its invoice, each with the invoice number, the customer's name and any credit kept.
                    Use this tool to review what was matched without a person and to undo one with \
                    reversePaymentApplication; use listUnappliedPayments instead for payments still waiting to \
                    be matched.
                    Preconditions: the caller needs accounting:payment:apply authority; UNDO is offered only to \
                    holders of accounting:payment:reverse and only while the application is not reversed.
                    Required inputs: since (ISO-8601 instant, at most 31 days back); page (0 or more) and size \
                    (1 to 100, default 50) are optional query parameters.
                    Emits an ACCOUNTING_PAYMENT_APPLICATION_AUTOMATIC_LIST_VIEW event and changes no state; \
                    totalElements counts every automatic application since the instant, reversed ones included.
                    Returns 400 VALIDATION_ERROR when since is missing, unparsable or more than 31 days back or \
                    size is outside 1 to 100, and 403 when the caller lacks the authority.
                    """,
            tags = {"Payment Applications"})
    @ApiResponse(responseCode = "200", description = "A page of automatic payment applications, newest first")
    @ApiResponse(
            responseCode = "400",
            description =
                    "VALIDATION_ERROR: since missing, unparsable or more than 31 days back, or size outside" + " 1-100",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:payment:apply",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_PAYMENT_APPLICATION_AUTOMATIC_LIST_VIEW", apiVersion = "1")
    public ResponseEntity<AutomaticPaymentApplicationsPage> listAutomaticPaymentApplications(
            @Parameter(
                            description = "Only applications made at or after this instant (ISO-8601), at most 31"
                                    + " days back",
                            required = true,
                            example = "2026-10-05T00:00:00Z")
                    @RequestParam(required = false)
                    @Nullable
                    String since,
            @Parameter(description = "Page index (0-based)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, 1 to 100", example = "50") @RequestParam(defaultValue = "50")
                    int size) {
        Instant from = parseSince(since);
        if (from.isBefore(Instant.now(clock).minus(Duration.ofDays(MAX_LOOKBACK_DAYS)))) {
            throw new InvalidRequestParameterException("since must be at most " + MAX_LOOKBACK_DAYS + " days back");
        }
        PageParameters.check(page, size, MAX_PAGE_SIZE);
        boolean canUndo = SecurityContextHelper.hasAuthority(AccountingPermissions.PAYMENT_REVERSE);
        log.debug("Listing automatic payment applications: page={}, size={}", page, size);
        return ResponseEntity.ok(queryService.listAutomatic(from, page, size, canUndo));
    }

    /** {@code since} as an instant; an offset other than Z is accepted and normalised. */
    static Instant parseSince(@Nullable String since) {
        if (since == null || since.isBlank()) {
            throw new InvalidRequestParameterException("since is required (ISO-8601 instant)");
        }
        try {
            return OffsetDateTime.parse(since.trim()).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidRequestParameterException("since must be an ISO-8601 instant, e.g. 2026-10-05T00:00:00Z");
        }
    }
}
