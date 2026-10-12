package com.positivity.order.internal.service;

import com.positivity.order.internal.repository.ExtPeopleContactUserLinkRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
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
 * Display names for the drawer actors pos-order serves as usernames (#2608; ADR-0064 forbids rendering a raw id): the
 * register session's opener and closer, a cash movement's cashier and approving manager, and the session-policy
 * history's actor. Resolved at read time from pos-order's own people-contact copy (ADR-0044 R1, §6): a username,
 * through its ACTIVE link, to its person's "First Last" (the #2481 rule: blanks dropped and trimmed), so a name that
 * reaches the copy later shows on old rows too. Never a cross-database join and never a call to another service. The
 * same rule as pos-accounting's {@code ActorDisplayNames} (#2670).
 *
 * <p>A name is {@code null} when it is not known: no ACTIVE link, a person not in the copy (or deleted), both names
 * blank, or the actor {@value #SYSTEM} (the default actor of an unauthenticated path, in any case). The username is
 * never the fallback. Names are CONFIDENTIAL (ADR-0072): served, never logged.
 */
@Service
@RequiredArgsConstructor
public class ActorDisplayNames {

    /** The actor of a path no person signed in to: a kind, never a person, matched in any case. */
    static final String SYSTEM = "system";

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
                .filter(username -> !username.isBlank() && !SYSTEM.equals(username.toLowerCase(Locale.ROOT)))
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
