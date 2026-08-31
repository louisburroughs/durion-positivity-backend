package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Permission grants for existing roles (#1613, D8 constraint 2). Nothing to resolve — both sides are
 * named by their business keys, and whether they exist is the owning service's question.
 */
@Component
public class RolePermissionLoaderStrategy implements DomainLoaderStrategy<RolePermissionLoaderRecord> {

    @Override
    public DomainType getDomainType() {
        return DomainType.SECURITY_ROLE_PERMISSION;
    }

    @Override
    public RolePermissionLoaderRecord mapRow(@NonNull Map<String, String> row) {
        RolePermissionLoaderRecord record = new RolePermissionLoaderRecord();
        record.setRoleName(row.get("roleName"));
        record.setPermissions(row.get("permissions"));
        return record;
    }

    @Override
    public List<String> validate(@NonNull RolePermissionLoaderRecord item) {
        List<String> errors = new ArrayList<>();
        if (LoaderValues.isBlank(item.getRoleName())) {
            errors.add("roleName is required");
        }
        if (LoaderValues.isBlank(item.getPermissions())) {
            // A grant row with no permissions is almost always a truncated export rather than a
            // deliberate no-op, and silently accepting it would leave the role inert.
            errors.add("permissions is required (semicolon-separated permission names)");
        }
        return errors;
    }
}
