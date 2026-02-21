package com.positivity.securityservice.internal.dto;

import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * Minimal create response for pricing snapshot creation.
 *
 * Issue: #41
 */
@Value
@Builder
public class PricingSnapshotCreatedResponse {
    UUID snapshotId;
}
