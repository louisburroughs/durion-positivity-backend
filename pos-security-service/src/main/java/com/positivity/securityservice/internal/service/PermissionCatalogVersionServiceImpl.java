package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.domain.PermissionBitsetCodec;
import com.positivity.securityservice.internal.enums.PermissionCode;
import com.positivity.securityservice.service.PermissionCatalogVersionService;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Implementation of PermissionCatalogVersionService.
 */
@Service
public class PermissionCatalogVersionServiceImpl implements PermissionCatalogVersionService {

    @Override
    public int getCatalogVersion() {
        return PermissionCode.CATALOG_VERSION;
    }

    @Override
    public int getPermissionCount() {
        return PermissionCode.values().length;
    }

    @Override
    public @NonNull List<String> decodePermissions(@NonNull String permBits, int permVer) {
        return PermissionBitsetCodec.decodeToPermissions(permBits, permVer)
                .stream()
                .map(PermissionCode::code)
                .sorted()
                .toList();
    }
}
