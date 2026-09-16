package com.positivity.people.internal.dto;

import java.time.LocalDate;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One credential as a feed or an operator states it (CAP-328). Either {@code skillCode} names the
 * registry row directly or {@code sourceCode}/{@code sourceCredentialCode} resolve to it through
 * the cross-reference; {@code issuer} defaults to the source code.
 */
@Value
@Builder
public class CredentialUpsertCommand {
    @Nullable
    String skillCode;

    @Nullable
    String sourceCode;

    @Nullable
    String sourceCredentialCode;

    @Nullable
    String issuer;

    @NonNull
    LocalDate issuedOn;

    @Nullable
    LocalDate expiresOn;

    @Nullable
    Integer proficiency;

    @Nullable
    UUID evidenceRef;

    @Nullable
    String sourceSystem;

    @Nullable
    String sourceVersion;
}
