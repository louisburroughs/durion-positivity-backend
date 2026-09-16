package com.positivity.domainevents.people;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * A row of the skill registry, published so consumers that validate skill references can hold
 * a replica instead of calling pos-people (CAP-329, ADR-0044 §6).
 *
 * <p>The registry is {@code @TenantGlobal} reference data (CAP-328): an ASE certification means
 * the same thing in every shop. pos-people publishes every row at startup, versioned by the row's
 * {@code updatedAt}, so a replica converges on replay and a re-seed reaches consumers as a new
 * version. {@code active=false} is a retirement, not a deletion — a service may still require
 * the skill, and the consumer must be able to say so.
 *
 * @param minGvwrClass lowest GVWR class (1–8) the skill covers (spec D13)
 * @param maxGvwrClass highest GVWR class (1–8) the skill covers
 */
public record SkillUpdatedV1(
        @NonNull UUID skillId,
        @NonNull String code,
        @NonNull String name,
        @NonNull String competenceCode,
        int minGvwrClass,
        int maxGvwrClass,
        boolean active) {

    public static final String EVENT_TYPE = "people.skill.updated";
    public static final int SCHEMA_VERSION = 1;
}
