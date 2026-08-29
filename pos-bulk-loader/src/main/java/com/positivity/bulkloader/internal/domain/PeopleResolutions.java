package com.positivity.bulkloader.internal.domain;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Employee-number lookups, for the packs whose files identify people the way an HR system does.
 *
 * <p>pos-people owns employee numbers, so this is the only way to turn one into the person id other
 * services key on.
 */
@Slf4j
public final class PeopleResolutions {

    private static final String PEOPLE_SERVICE_ID = "people";

    private PeopleResolutions() {}

    /** The person id behind an employee number, or empty when no employee carries it. */
    @NonNull
    public static Optional<String> personId(@NonNull ResolutionContext context, @NonNull String employeeNumber) {
        String number = employeeNumber.trim();
        return context.memoize("person:" + number.toLowerCase(Locale.ROOT), () -> {
            String uri = UriComponentsBuilder.fromPath("/v1/people/employees/by-number/{employeeNumber}")
                    .encode(StandardCharsets.UTF_8)
                    .buildAndExpand(number)
                    .toUriString();
            Optional<String> personId = context.get(PEOPLE_SERVICE_ID, uri, Map.class)
                    .map(body -> body.get("personId"))
                    .map(Object::toString)
                    .filter(value -> !value.isBlank());
            if (personId.isEmpty()) {
                log.warn("Employee number '{}' resolved to no person", number);
            }
            return personId;
        });
    }
}
