package com.positivity.people.internal.client.dto;

import java.util.UUID;

/**
 * Minimal location payload returned by the location service.
 *
 * Issue: #79
 */
public record LocationSummaryResponse(UUID id, String name, boolean active) {}
