package com.positivity.inboundmail.internal.dto;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * What staging hands back for one stored attachment: the {@code ref} the command carries and the size
 * and digest the consumer checks the fetched bytes against.
 */
public record StagedAttachmentRef(
        @NonNull UUID ref,
        @NonNull String declaredType,
        long sizeBytes,
        @NonNull String sha256) {}
