package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * User-to-person links.
 *
 * <p>The person is named by employee number and resolved against pos-people; the username is left
 * alone, because pos-security-service owns usernames and resolves them when the batch lands.
 */
@Component
public class UserPersonLinkLoaderStrategy implements DomainLoaderStrategy<UserPersonLinkLoaderRecord> {

    @Override
    public DomainType getDomainType() {
        return DomainType.USER_PERSON_LINK;
    }

    @Override
    public UserPersonLinkLoaderRecord mapRow(@NonNull Map<String, String> row) {
        UserPersonLinkLoaderRecord record = new UserPersonLinkLoaderRecord();
        record.setUsername(row.get("username"));
        record.setEmployeeNumber(row.get("employeeNumber"));
        record.setPersonId(row.get("personId"));
        return record;
    }

    @Override
    @NonNull
    public UserPersonLinkLoaderRecord resolve(
            @NonNull UserPersonLinkLoaderRecord item, @NonNull ResolutionContext context) {
        if (LoaderValues.isBlank(item.getPersonId()) && LoaderValues.isPresent(item.getEmployeeNumber())) {
            PeopleResolutions.personId(context, item.getEmployeeNumber()).ifPresent(item::setPersonId);
        }
        return item;
    }

    @Override
    public List<String> validate(@NonNull UserPersonLinkLoaderRecord item) {
        List<String> errors = new ArrayList<>();
        if (LoaderValues.isBlank(item.getUsername())) {
            errors.add("username is required");
        }
        LoaderValues.requireUuid(item.getPersonId(), "personId", "an employeeNumber that resolves to one", errors);
        return errors;
    }
}
