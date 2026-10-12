package com.positivity.order.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.order.PostgresSliceTestBase;
import com.positivity.order.internal.entity.ExtPeopleContactPerson;
import com.positivity.order.internal.entity.ExtPeopleContactUserLink;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The drawer actor-name lookup ({@link ExtPeopleContactUserLinkRepository#findActiveNames}, #2608) against the real
 * PostgreSQL schema (V11, with Hibernate {@code validate}): a username resolves only through an ACTIVE link to a person
 * the copy holds and has not tombstoned, and a whole response's usernames resolve in one statement.
 */
@DisplayName("ExtPeopleContactUserLinkRepository — drawer actor names on PostgreSQL (#2608)")
class ExtPeopleContactUserLinkRepositoryTest extends PostgresSliceTestBase {

    private static final Instant NOW = Instant.parse("2026-10-12T09:00:00Z");

    @Autowired
    private ExtPeopleContactUserLinkRepository links;

    @Autowired
    private ExtPeopleContactPersonRepository persons;

    private UUID person(String first, String last, boolean deleted) {
        UUID personId = UUID.randomUUID();
        persons.saveAndFlush(ExtPeopleContactPerson.builder()
                .personId(personId)
                .firstName(first)
                .lastName(last)
                .deleted(deleted)
                .aggregateVersion(1L)
                .updatedAt(NOW)
                .build());
        return personId;
    }

    private void link(String username, UUID personId, String status) {
        links.saveAndFlush(ExtPeopleContactUserLink.builder()
                .linkId(UUID.randomUUID())
                .personId(personId)
                .username(username)
                .status(status)
                .aggregateVersion(1L)
                .updatedAt(NOW)
                .build());
    }

    @Test
    @DisplayName("an ACTIVE link to a copied person answers its names; every other case answers nothing")
    void onlyAnActiveLinkToALivePersonAnswers() {
        link("dana.reyes", person("Dana", "Reyes", false), ExtPeopleContactUserLink.ACTIVE);
        link("inactive.link", person("Ina", "Active", false), "INACTIVE");
        link("removed.link", person("Rem", "Oved", false), ExtPeopleContactUserLink.REMOVED);
        link("deleted.person", person(null, null, true), ExtPeopleContactUserLink.ACTIVE);
        link("missing.person", UUID.randomUUID(), ExtPeopleContactUserLink.ACTIVE);

        List<ExtPeopleContactUserLinkRepository.LinkedName> rows = links.findActiveNames(
                List.of("dana.reyes", "inactive.link", "removed.link", "deleted.person", "missing.person", "no.link"));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getUsername()).isEqualTo("dana.reyes");
            assertThat(row.getFirstName()).isEqualTo("Dana");
            assertThat(row.getLastName()).isEqualTo("Reyes");
        });
    }

    @Test
    @DisplayName("several usernames resolve together, in one statement")
    void severalUsernamesResolveTogether() {
        link("dana.reyes", person("Dana", "Reyes", false), ExtPeopleContactUserLink.ACTIVE);
        link("sam.ortiz", person("Sam", "Ortiz", false), ExtPeopleContactUserLink.ACTIVE);

        assertThat(links.findActiveNames(List.of("dana.reyes", "sam.ortiz")))
                .extracting(ExtPeopleContactUserLinkRepository.LinkedName::getUsername)
                .containsExactly("dana.reyes", "sam.ortiz");
    }
}
