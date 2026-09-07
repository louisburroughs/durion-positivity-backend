package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Package membership. Nothing to resolve: both sides are named by codes the catalog already knows,
 * and a code that names nothing is the catalog's row to reject, with the name it could not find.
 */
@Component
public class ServicePackageMemberLoaderStrategy implements DomainLoaderStrategy<ServicePackageMemberLoaderRecord> {

    @Override
    public DomainType getDomainType() {
        return DomainType.SERVICE_PACKAGE_MEMBER;
    }

    @Override
    public ServicePackageMemberLoaderRecord mapRow(@NonNull Map<String, String> row) {
        ServicePackageMemberLoaderRecord member = new ServicePackageMemberLoaderRecord();
        member.setPackageCode(row.get("packageCode"));
        member.setOperationCode(row.get("operationCode"));
        member.setSequence(row.get("sequence"));
        member.setQuantity(row.get("quantity"));
        member.setRequired(row.get("required"));
        return member;
    }

    @Override
    public List<String> validate(@NonNull ServicePackageMemberLoaderRecord item) {
        List<String> errors = new ArrayList<>();
        if (LoaderValues.isBlank(item.getPackageCode())) {
            errors.add("packageCode is required");
        }
        if (LoaderValues.isBlank(item.getOperationCode())) {
            errors.add("operationCode is required");
        }
        LoaderValues.requireIntegerOrBlank(item.getSequence(), "sequence", errors);
        LoaderValues.requireDecimalOrBlank(item.getQuantity(), "quantity", errors);
        return errors;
    }
}
