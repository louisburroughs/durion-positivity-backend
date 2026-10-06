package com.positivity.supplier.internal.vendor.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Read model of a vendor (#2516). {@code remitTo} is the approved remit-to only; a pending change is
 * read through the remit-to change list.
 *
 * @param vendorId vendor identity (UUIDv7); never display text (ADR-0064)
 * @param vendorNumber the number people quote; never changes
 * @param legalName legal name
 * @param displayName name screens show
 * @param taxRegistrations tax registrations
 * @param remitTo approved remit-to; {@code null} while there is none
 * @param remitToVersion 0 with no remit-to, 1 when given at creation, +1 per approved change
 * @param remitToChangedAt when the current remit-to took effect
 * @param remitToRequestedBy who requested the current remit-to
 * @param remitToApprovedBy who approved it; {@code null} for a remit-to given at creation
 * @param defaultPaymentTerms {@code DUE_ON_RECEIPT} or {@code NET<n>}; {@code null} only on a vendor
 *     created from a pre-existing profile and not yet completed
 * @param defaultCurrency ISO 4217; {@code null} on the same vendors
 * @param status {@code ACTIVE} or {@code INACTIVE}
 * @param statusChangedAt when the status last changed
 * @param statusReason the reason given for the last status change
 * @param createdAt creation time
 * @param createdBy creator
 * @param updatedAt last change
 * @param updatedBy last changer
 * @param version optimistic-lock version; send it back on update
 */
@Schema(description = "A vendor. remitTo is the approved remit-to; pending changes are listed separately.")
public record VendorView(
        @Schema(description = "Vendor identity (UUIDv7).", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b") @NonNull
        UUID vendorId,

        @Schema(description = "Number people quote; never changes.", example = "V-000001") @NonNull
        String vendorNumber,

        @Schema(description = "Legal name.", example = "Michelin North America, Inc.") @NonNull
        String legalName,

        @Schema(description = "Name screens show.", example = "Michelin") @NonNull
        String displayName,

        @Schema(description = "Tax registrations.") @NonNull List<TaxRegistrationDto> taxRegistrations,

        @Schema(description = "Approved remit-to; null while there is none.") @Nullable
        RemitToDto remitTo,

        @Schema(description = "0 with no remit-to, 1 when given at creation, +1 per approved change.", example = "1")
        int remitToVersion,

        @Schema(description = "When the current remit-to took effect.") @Nullable
        Instant remitToChangedAt,

        @Schema(description = "Who requested the current remit-to.", example = "clerk.a") @Nullable
        String remitToRequestedBy,

        @Schema(
                description = "Who approved the current remit-to; null when given at creation.",
                example = "controller.b")
        @Nullable
        String remitToApprovedBy,

        @Schema(description = "DUE_ON_RECEIPT or NET<n>.", example = "NET30") @Nullable
        String defaultPaymentTerms,

        @Schema(description = "ISO 4217 currency code.", example = "USD") @Nullable
        String defaultCurrency,

        @Schema(description = "ACTIVE or INACTIVE.", example = "ACTIVE") @NonNull
        VendorStatus status,

        @Schema(description = "When the status last changed.") @Nullable
        Instant statusChangedAt,

        @Schema(description = "Reason given for the last status change.") @Nullable
        String statusReason,

        @Schema(description = "Creation time.") @NonNull Instant createdAt,

        @Schema(description = "Creator.", example = "clerk.a") @NonNull
        String createdBy,

        @Schema(description = "Last change time.") @NonNull Instant updatedAt,

        @Schema(description = "Last changer.", example = "clerk.a") @Nullable
        String updatedBy,

        @Schema(description = "Optimistic-lock version; send it back on update.", example = "0")
        long version) {}
