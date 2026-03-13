package com.positivity.securityservice.internal.config;

import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.enums.PermissionCode;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Startup validator that checks permission bit index integrity against the
 * PermissionCode catalog. Emits structured WARN logs for:
 * <ul>
 * <li>Known catalog permissions (PermissionCode match) with no bit_index
 * assigned</li>
 * <li>Permissions with a non-null bit_index that mismatches the catalog
 * entry</li>
 * <li>DB permissions not present in the catalog (unknown permissions)</li>
 * </ul>
 */
@Component
public class PermissionBitIndexValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PermissionBitIndexValidator.class);

    private final PermissionRepository permissionRepository;

    public PermissionBitIndexValidator(@NonNull PermissionRepository permissionRepository) {
        this.permissionRepository = permissionRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Permission p : permissionRepository.findAll()) {
            PermissionCode.fromCode(p.getName()).ifPresentOrElse(
                    catalogEntry -> {
                        if (p.getBitIndex() == null) {
                            log.warn("Permission '{}' matches catalog entry {} but has no bit index assignment",
                                    p.getName(), catalogEntry.name());
                        } else if (p.getBitIndex() != catalogEntry.bitIndex()) {
                            log.warn("Permission '{}' has bit index {} but catalog expects {}",
                                    p.getName(), p.getBitIndex(), catalogEntry.bitIndex());
                        }
                    },
                    () -> log.warn(
                            "Permission '{}' is not in the PermissionCode catalog; bit index will not be assigned",
                            p.getName()));
        }
    }
}
