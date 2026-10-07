package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * The answer to a step-up credential check (CAP:550 S16, #2512; AW31): who the credentials belong to and
 * whether that person holds the permission asked about. No token is issued and no session is opened.
 *
 * @param userId the verified person's user id
 * @param holdsPermission whether the person's effective roles grant the permission asked about
 */
@Schema(description = "The verified person and whether they hold the permission asked about")
public record StepUpResponse(UUID userId, boolean holdsPermission) {}
