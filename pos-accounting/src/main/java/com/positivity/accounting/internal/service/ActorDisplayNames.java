package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.repository.ExtPeopleContactUserLinkRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Display names for the AP actors a read serves as usernames (AP reads #2670; ruling 6079195896 Q4; P8, ADR-0064).
 * Resolved at read time from accounting's own people-contact copy (ADR-0044 R1, §6): a username, through its ACTIVE
 * link, to its person's "First Last" (the #2481 rule: blanks dropped and trimmed). Never a cross-database join and
 * never a call to another service.
 *
 * <p>A name is {@code null} when it is not known: no ACTIVE link, a person not in the copy (or deleted), both names
 * blank, or the actor {@value #SYSTEM}. The username is never the fallback. Names are CONFIDENTIAL (ADR-0072): served,
 * never logged.
 */
@Service
@RequiredArgsConstructor
public class ActorDisplayNames {

    /** The actor of an automatic decision: a kind, rendered "Automatic", never a person. */
    static final String SYSTEM = "SYSTEM";

    private final ExtPeopleContactUserLinkRepository links;

    /**
     * The names of {@code usernames}, in one query whatever their number; usernames without a known name are absent.
     *
     * @param usernames the actors of one response, nulls and repeats allowed
     * @return username to display name, known names only
     */
    @Transactional(readOnly = true)
    public @NonNull Map<String, String> namesOf(@NonNull Collection<@Nullable String> usernames) {
        Set<String> wanted = usernames.stream()
                .filter(Objects::nonNull)
                .filter(username -> !username.isBlank() && !SYSTEM.equals(username))
                .collect(Collectors.toCollection(TreeSet::new));
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        for (ExtPeopleContactUserLinkRepository.LinkedName row : links.findActiveNames(wanted)) {
            String name = personName(row.getFirstName(), row.getLastName());
            if (name != null) {
                names.putIfAbsent(row.getUsername(), name);
            }
        }
        return names;
    }

    /** {@code username}'s name in {@code names}, or null for no username; a convenience for response builders. */
    static @Nullable String nameOf(@NonNull Map<String, String> names, @Nullable String username) {
        return username == null ? null : names.get(username);
    }

    /**
     * A person's display name, "First Last" with blanks dropped and trimmed (#2481). {@code null} when both are blank.
     */
    static @Nullable String personName(@Nullable String firstName, @Nullable String lastName) {
        String name = Stream.of(firstName, lastName)
                .filter(part -> part != null && !part.isBlank())
                .map(String::strip)
                .collect(Collectors.joining(" "));
        return name.isEmpty() ? null : name;
    }
}
