package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.repository.ExtPeopleContactUserLinkRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * AP reads #2670 AC 6-8: a username resolves, through its ACTIVE link, to "First Last" (the #2481 rule), in one query
 * per response; an unknown actor, a blank-named person and SYSTEM have no name, and the username is never the name.
 */
@DisplayName("ActorDisplayNames — AP actor names from the people-contact copy (#2670)")
class ActorDisplayNamesTest {

    private final ExtPeopleContactUserLinkRepository links = mock(ExtPeopleContactUserLinkRepository.class);
    private final ActorDisplayNames names = new ActorDisplayNames(links);

    static ExtPeopleContactUserLinkRepository.LinkedName row(String username, String first, String last) {
        return new ExtPeopleContactUserLinkRepository.LinkedName() {
            @Override
            public String getUsername() {
                return username;
            }

            @Override
            public String getFirstName() {
                return first;
            }

            @Override
            public String getLastName() {
                return last;
            }
        };
    }

    @Test
    @DisplayName("the #2481 format: First Last, blanks dropped and trimmed; null when both are blank")
    void format() {
        assertThat(ActorDisplayNames.personName("Dana", "Reyes")).isEqualTo("Dana Reyes");
        assertThat(ActorDisplayNames.personName("  Dana ", null)).isEqualTo("Dana");
        assertThat(ActorDisplayNames.personName(" ", "Reyes ")).isEqualTo("Reyes");
        assertThat(ActorDisplayNames.personName(" ", "")).isNull();
        assertThat(ActorDisplayNames.personName(null, null)).isNull();
    }

    @Test
    @DisplayName("AC 6: a username with an ACTIVE link serves its person's name")
    void linkedUsername() {
        when(links.findActiveNames(anyCollection())).thenReturn(List.of(row("controller.cfo", "Dana", "Reyes")));

        assertThat(names.namesOf(List.of("controller.cfo"))).containsExactly(Map.entry("controller.cfo", "Dana Reyes"));
    }

    @Test
    @DisplayName("AC 7: no link (or a non-ACTIVE one), a person not in the copy, blank names and SYSTEM serve no name;"
            + " the username is never the fallback")
    void unknownActorsHaveNoName() {
        // The query answers only ACTIVE links whose person is in the copy, so the others have no row at all.
        when(links.findActiveNames(anyCollection())).thenReturn(List.of(row("blank.names", " ", "")));

        Map<String, String> resolved = names.namesOf(
                Arrays.asList("no.link", "removed.link", "deleted.person", "blank.names", "SYSTEM", null));

        assertThat(resolved).isEmpty();
        assertThat(ActorDisplayNames.nameOf(resolved, "no.link")).isNull();
        assertThat(ActorDisplayNames.nameOf(resolved, "SYSTEM")).isNull();
        assertThat(ActorDisplayNames.nameOf(resolved, null)).isNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> asked = ArgumentCaptor.forClass(Collection.class);
        verify(links).findActiveNames(asked.capture());
        assertThat(asked.getValue())
                .as("SYSTEM is a kind, never looked up as a person")
                .doesNotContain("SYSTEM")
                .doesNotContainNull();
    }

    @Test
    @DisplayName("SYSTEM alone, or no actor at all, asks nothing")
    void onlySystem() {
        assertThat(names.namesOf(Arrays.asList("SYSTEM", null, " "))).isEmpty();
        verify(links, never()).findActiveNames(anyCollection());
    }

    @Test
    @DisplayName("AC 8: a 20-row page with 5 distinct users resolves in one query, each user asked once")
    void oneQueryPerPage() {
        List<String> page = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            page.add("user." + (i % 5));
        }
        when(links.findActiveNames(anyCollection()))
                .thenReturn(List.of(row("user.0", "Ana", "Ortiz"), row("user.3", "Ben", "Okafor")));

        Map<String, String> resolved = names.namesOf(page);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> asked = ArgumentCaptor.forClass(Collection.class);
        verify(links, times(1)).findActiveNames(asked.capture());
        assertThat(asked.getValue()).containsExactlyInAnyOrder("user.0", "user.1", "user.2", "user.3", "user.4");
        assertThat(resolved).containsOnly(Map.entry("user.0", "Ana Ortiz"), Map.entry("user.3", "Ben Okafor"));
    }
}
