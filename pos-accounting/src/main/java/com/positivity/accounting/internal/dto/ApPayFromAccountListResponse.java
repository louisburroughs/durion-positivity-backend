package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The accounts a vendor payment may come from today (AP reads #2670; CAP:550 S42, AW41): exactly the ones the pay
 * command would accept, computed by the same rule ({@code ApPayFromAccounts}). Informational: the payment still checks
 * eligibility when it executes.
 *
 * @param asOf                 the business date the payment would execute on
 * @param currencyCode         the functional currency, the one every listed account is in
 * @param defaultBankAccountId the single eligible account, which an omitted {@code bankAccountId} resolves to; null
 *                             with none or several
 * @param accounts             the eligible accounts, by account number
 */
@Schema(
        name = "ApPayFromAccountListResponse",
        description = "The bank accounts a vendor payment may come from today, by the pay command's own rule")
public record ApPayFromAccountListResponse(
        @Schema(
                description = "The business date the payment would execute on",
                example = "2026-10-09",
                requiredMode = REQUIRED)
        LocalDate asOf,

        @Schema(
                description = "ISO 4217 code of the functional currency, the one every listed account is in",
                example = "USD",
                requiredMode = REQUIRED)
        String currencyCode,

        @Schema(
                description = "The single eligible account, which an omitted bankAccountId resolves to; null when"
                        + " there is none or more than one",
                example = "0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f1000",
                requiredMode = NOT_REQUIRED)
        @Nullable
        UUID defaultBankAccountId,

        @ArraySchema(
                arraySchema =
                        @Schema(
                                description = "The eligible accounts by account number; empty when none is set up, and"
                                        + " a payment then answers 400 fieldErrors[bankAccountId]",
                                requiredMode = REQUIRED))
        List<Account> accounts) {

    public ApPayFromAccountListResponse {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
    }

    /**
     * One account a payment may come from.
     *
     * @param bankAccountId the GL account id, the value {@code POST /v1/accounting/ap/payments} takes
     * @param accountNumber the GL account number
     * @param accountName   the GL account name
     * @param bankName      the bank's name from the bank-account profile; null without one
     * @param accountMask   the masked last digits from the profile; null without one. CONFIDENTIAL: never logged
     */
    @Schema(name = "ApPayFromAccount", description = "One BANK_CASH account a vendor payment may come from")
    public record Account(
            @Schema(
                    description = "The GL account id, the bankAccountId POST /v1/accounting/ap/payments takes",
                    example = "0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f1000",
                    requiredMode = REQUIRED)
            UUID bankAccountId,

            @Schema(description = "The GL account number", example = "1000", requiredMode = REQUIRED)
            String accountNumber,

            @Schema(description = "The GL account name", example = "Operating Account", requiredMode = REQUIRED)
            String accountName,

            @Schema(
                    description = "The bank's name from the bank-account profile; null without a profile",
                    example = "First National",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            String bankName,

            @Schema(
                    description = "The masked last digits of the bank account number from the profile, display only;"
                            + " null without a profile. A full account number is never served",
                    example = "4321",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            String accountMask) {

        @Override
        public String toString() {
            // accountMask is a CONFIDENTIAL masked derivative (ADR-0072): never printed.
            return "Account[bankAccountId=" + bankAccountId + ", accountNumber=" + accountNumber + ", accountName="
                    + accountName + ", bankName=" + bankName + "]";
        }
    }
}
