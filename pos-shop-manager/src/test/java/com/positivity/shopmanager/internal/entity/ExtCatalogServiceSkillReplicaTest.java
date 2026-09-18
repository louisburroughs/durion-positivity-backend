package com.positivity.shopmanager.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExtCatalogServiceSkillReplicaTest {

    @Test
    void bothBoundsNull_appliesToEveryVehicleIncludingAnUndeterminedClass() {
        ExtCatalogServiceSkillReplica any =
                ExtCatalogServiceSkillReplica.builder().build();

        assertThat(any.appliesTo(null)).isTrue();
        assertThat(any.appliesTo(5)).isTrue();
    }

    @Test
    void closedRange_appliesOnlyInsideIt() {
        ExtCatalogServiceSkillReplica light = ExtCatalogServiceSkillReplica.builder()
                .minGvwrClass(1)
                .maxGvwrClass(3)
                .build();

        assertThat(light.appliesTo(1)).isTrue();
        assertThat(light.appliesTo(3)).isTrue();
        assertThat(light.appliesTo(4)).isFalse();
        assertThat(light.appliesTo(null)).isFalse();
    }

    /** The columns are independently nullable; a single absent bound used to unbox null. */
    @Test
    void oneBoundNull_leavesThatSideOpen() {
        ExtCatalogServiceSkillReplica heavyAndUp =
                ExtCatalogServiceSkillReplica.builder().minGvwrClass(4).build();
        ExtCatalogServiceSkillReplica upToLight =
                ExtCatalogServiceSkillReplica.builder().maxGvwrClass(3).build();

        assertThat(heavyAndUp.appliesTo(8)).isTrue();
        assertThat(heavyAndUp.appliesTo(3)).isFalse();
        assertThat(heavyAndUp.appliesTo(null)).isFalse();
        assertThat(upToLight.appliesTo(1)).isTrue();
        assertThat(upToLight.appliesTo(4)).isFalse();
        assertThat(upToLight.appliesTo(null)).isFalse();
    }
}
