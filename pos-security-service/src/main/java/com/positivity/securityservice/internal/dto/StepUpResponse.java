package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * The answer to a step-up credential check (CAP:550 S16, #2512; AW31): who the credentials belong to,
 * whether that person holds the permission asked about, and that permission's location scope for them —
 * the same material a token of theirs would carry (ADR-0061 §2), so the caller decides their reach at a
 * location with its own location replica. No token is issued and no session is opened.
 *
 * @param userId the verified person's user id
 * @param holdsPermission whether the person's effective roles grant the permission asked about
 * @param financialScoped whether that grant is location-scoped on the FINANCIAL dimension
 * @param otherScoped whether that grant is location-scoped on the OTHER dimension
 * @param assignedLocationIds the person's assigned location nodes today; empty when the grant is global,
 *     and also when it is scoped but the person has no assignment (fail closed)
 */
@Schema(description = "The verified person, whether they hold the permission, and its location scope")
public record StepUpResponse(
        UUID userId,
        boolean holdsPermission,
        boolean financialScoped,
        boolean otherScoped,
        List<UUID> assignedLocationIds) {}
