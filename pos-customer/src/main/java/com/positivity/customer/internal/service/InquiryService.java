package com.positivity.customer.internal.service;

import com.positivity.customer.internal.dto.InquiryResponse;
import com.positivity.customer.internal.dto.PagedResponse;
import com.positivity.customer.internal.dto.SubmitInquiryRequest;
import com.positivity.customer.internal.enums.InquiryStatus;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Public API for inbound inquiry capture and conversion (Story #1154).
 *
 * <p>An inquiry is an unverified, often duplicated, frequently abandoned request. It is held
 * flat here rather than being pushed into the identity store on arrival; a canonical person is
 * created only at conversion, which is also where duplicate detection runs.
 *
 * <p>Lives in pos-customer, not {@code pos-inquiry} — that module is supplier-scoped
 * (decision O-6).
 */
public interface InquiryService {

    /** Capture an inquiry taken by a CSR. */
    @NonNull
    InquiryResponse capture(@NonNull SubmitInquiryRequest request);

    /**
     * Capture an inquiry submitted through the unauthenticated public form.
     *
     * @param sourceFingerprint truncated, hashed submitter origin used only for abuse triage
     * @return the new inquiry id
     */
    @NonNull
    UUID captureFromPublicForm(@NonNull SubmitInquiryRequest request, @Nullable String sourceFingerprint);

    @NonNull
    InquiryResponse get(@NonNull UUID inquiryId);

    @NonNull
    PagedResponse<InquiryResponse> list(@Nullable InquiryStatus status, int page, int size);

    @NonNull
    InquiryResponse assign(@NonNull UUID inquiryId, @Nullable String assignedTo);

    /** Move to CONTACTED or CLOSED. CONVERTED is reachable only through {@link #convert}. */
    @NonNull
    InquiryResponse updateStatus(
            @NonNull UUID inquiryId, @NonNull InquiryStatus status, @Nullable String resolutionNote);

    /**
     * Turn the inquiry into a party in {@code PROSPECT} lifecycle stage.
     *
     * <p>When {@code existingPartyId} is supplied the inquiry is linked to that party instead
     * of creating a new one — the duplicate-resolution path, so a returning customer enquiring
     * again does not fork their record.
     */
    @NonNull
    InquiryResponse convert(@NonNull UUID inquiryId, @Nullable UUID existingPartyId);
}
