package com.positivity.people.internal.client.dto;

import java.util.UUID;

public record LocationValidationResponse(UUID locationId, boolean exists, boolean active) {}
