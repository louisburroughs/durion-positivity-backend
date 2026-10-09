package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The expense categories an approver may choose for a vendor bill (AP reads #2670; AW39, AW47): the active {@code
 * VENDOR_BILL} {@code EXPENSE_<CODE>} keys with the account each resolves to on {@code asOf}, in the server's order.
 *
 * @param asOf       the tenant's business date the accounts are resolved on
 * @param categories the active expense keys, by label (case-insensitive, a null label as its key), then key
 */
@Schema(
        name = "VendorBillExpenseCategoryListResponse",
        description = "The active VENDOR_BILL expense keys an approver may choose, with the account each posts to")
public record VendorBillExpenseCategoryListResponse(
        @Schema(
                description = "The tenant's business date the accounts are resolved on",
                example = "2026-10-09",
                requiredMode = REQUIRED)
        LocalDate asOf,

        @ArraySchema(
                arraySchema =
                        @Schema(
                                description = "The active expense keys in the server's order (by label, then key);"
                                        + " the client keeps this order; empty when the tenant has none",
                                requiredMode = REQUIRED))
        List<Category> categories) {

    public VendorBillExpenseCategoryListResponse {
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    /**
     * One expense category.
     *
     * @param mappingKey    the key a classification or a vendor default names
     * @param label         the key's description, or null when it has none
     * @param accountNumber the number of the account the key resolves to on {@code asOf}; null without a mapping
     * @param accountName   that account's name; null without a mapping
     */
    @Schema(name = "VendorBillExpenseCategory", description = "One active VENDOR_BILL expense key")
    public record Category(
            @Schema(
                    description = "The VENDOR_BILL expense key, EXPENSE_<CODE>, as a classification names it",
                    example = "EXPENSE_SHOP_SUPPLIES",
                    requiredMode = REQUIRED)
            String mappingKey,

            @Schema(
                    description = "The key's label, its description; null when it has none",
                    example = "Shop supplies",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            String label,

            @Schema(
                    description = "The number of the account the key resolves to on asOf; null when no mapping is"
                            + " effective that day (an approval naming it answers 422 GL_MAPPING_NOT_CONFIGURED)",
                    example = "6340",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            String accountNumber,

            @Schema(
                    description = "The name of that account; null when no mapping is effective that day",
                    example = "Shop Supplies & Consumables",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            String accountName) {}
}
