package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/** User accounts. Nothing to resolve — and, deliberately, no password to carry. */
@Component
public class SecurityUserLoaderStrategy implements DomainLoaderStrategy<SecurityUserLoaderRecord> {

    @Override
    public DomainType getDomainType() {
        return DomainType.SECURITY_USER;
    }

    @Override
    public SecurityUserLoaderRecord mapRow(@NonNull Map<String, String> row) {
        SecurityUserLoaderRecord record = new SecurityUserLoaderRecord();
        record.setUsername(row.get("username"));
        record.setRoles(row.get("roles"));
        return record;
    }

    @Override
    public List<String> validate(@NonNull SecurityUserLoaderRecord item) {
        List<String> errors = new ArrayList<>();
        if (LoaderValues.isBlank(item.getUsername())) {
            errors.add("username is required");
        }
        if (LoaderValues.isBlank(item.getRoles())) {
            // An account with no roles can sign in and do nothing, which looks like a broken
            // account rather than a deliberately empty one.
            errors.add("roles is required (semicolon-separated role names)");
        }
        return errors;
    }
}
