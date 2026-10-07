package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Record a bank deposit of closed register sessions' drawer cash (CAP:550 S18, #2514; SPEC-accounting-workspace §4.5,
 * §7.1 "Record / reverse deposit"). The server computes every amount from the sessions (P7); the request states the
 * currency they are in (ADR-0067 R-1).
 */
@Schema(description = "Record a bank deposit of whole closed register sessions' drawer cash")
public record DepositRecordRequest(
        @Schema(
                description = "The bank account the cash went to: an active, reconcilable BANK_CASH account in"
                        + " functional currency",
                example = "019a0000-0000-7000-8000-00000000b000",
                requiredMode = REQUIRED)
        @Nullable
        UUID bankGlAccountId,

        @Schema(
                description = "The day the cash reached the bank; the entry is dated on it and passes the period gate",
                example = "2026-10-08",
                requiredMode = REQUIRED)
        @Nullable
        LocalDate depositDate,

        @Schema(
                description = "The ISO 4217 code of the sessions' amounts (ADR-0067); it must be the tenant's"
                        + " functional currency, else 422 CURRENCY_NOT_SUPPORTED",
                example = "USD",
                minLength = 3,
                maxLength = 3,
                requiredMode = REQUIRED)
        @Nullable
        String currencyCode,

        @ArraySchema(
                arraySchema =
                        @Schema(
                                description = "The undeposited sessions the deposit takes, each whole: all its bank"
                                        + " drops and its whole clearing net",
                                requiredMode = REQUIRED),
                schema = @Schema(example = "019a0000-0000-7000-8000-00000000c001"),
                minItems = 1,
                maxItems = DepositRecordRequest.MAX_SESSIONS,
                uniqueItems = true)
        @Nullable
        List<UUID> sessionIds,

        @Schema(
                description = "Caller-generated UUIDv7 naming this request; a replay returns the first result",
                example = "019a0000-0000-7000-8000-000000000301",
                requiredMode = REQUIRED)
        @Nullable
        UUID requestId,

        @Schema(
                description = "The bank's deposit slip number, kept with the deposit",
                example = "DS-20261008-01",
                maxLength = DepositRecordRequest.MAX_SLIP_REFERENCE,
                requiredMode = NOT_REQUIRED)
        @Nullable
        String depositSlipReference,

        @Schema(
                description = "Why the deposit posts into a closed period; honoured only with"
                        + " accounting:period:override",
                example = "Deposit slip found after the month was closed",
                maxLength = CashRequests.MAX_JUSTIFICATION,
                requiredMode = NOT_REQUIRED)
        @Nullable
        String overrideJustification) {

    /** Most sessions one deposit takes. */
    public static final int MAX_SESSIONS = 200;

    /** Longest deposit slip reference. */
    public static final int MAX_SLIP_REFERENCE = 100;

    /**
     * Refuses a body the command cannot act on.
     *
     * @throws InvalidRequestParameterException (400 {@code VALIDATION_ERROR} naming the field)
     */
    public void requireValid() {
        if (bankGlAccountId == null) {
            throw InvalidRequestParameterException.forField("bankGlAccountId", "bankGlAccountId is required");
        }
        if (depositDate == null) {
            throw InvalidRequestParameterException.forField("depositDate", "depositDate is required");
        }
        CashRequests.requireCurrencyCode(currencyCode, "currencyCode");
        if (sessionIds == null || sessionIds.isEmpty()) {
            throw InvalidRequestParameterException.forField("sessionIds", "sessionIds must name at least one session");
        }
        if (sessionIds.size() > MAX_SESSIONS) {
            throw InvalidRequestParameterException.forField(
                    "sessionIds", "sessionIds must not name more than " + MAX_SESSIONS + " sessions");
        }
        Set<UUID> seen = new HashSet<>();
        for (UUID sessionId : sessionIds) {
            if (sessionId == null) {
                throw InvalidRequestParameterException.forField("sessionIds", "sessionIds must not contain null");
            }
            if (!seen.add(sessionId)) {
                throw InvalidRequestParameterException.forField(
                        "sessionIds", "sessionIds names session " + sessionId + " more than once");
            }
        }
        if (requestId == null) {
            throw InvalidRequestParameterException.forField("requestId", "requestId is required");
        }
        if (depositSlipReference != null && depositSlipReference.trim().length() > MAX_SLIP_REFERENCE) {
            throw InvalidRequestParameterException.forField(
                    "depositSlipReference",
                    "depositSlipReference must not exceed " + MAX_SLIP_REFERENCE + " characters");
        }
        if (overrideJustification != null && overrideJustification.length() > CashRequests.MAX_JUSTIFICATION) {
            throw InvalidRequestParameterException.forField(
                    "overrideJustification",
                    "overrideJustification must not exceed " + CashRequests.MAX_JUSTIFICATION + " characters");
        }
    }
}
