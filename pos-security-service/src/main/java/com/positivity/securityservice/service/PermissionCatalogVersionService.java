package com.positivity.securityservice.service;

import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Service for catalog versioning and permission bitset diagnostics.
 */
public interface PermissionCatalogVersionService {

    /**
     * Returns the current catalog version (from PermissionCode.CATALOG_VERSION).
     *
     * @return catalog version integer
     */
    int getCatalogVersion();

    /**
     * Returns the total number of permission codes registered in the catalog.
     *
     * @return count of PermissionCode enum constants
     */
    int getPermissionCount();

    /**
     * Decodes a Base64URL perm_bits string into the list of permission code
     * strings.
     *
     * @param permBits Base64URL-encoded BitSet
     * @param permVer  catalog version used to encode perm_bits
     * @return ordered list of permission code strings decoded from the bitset
     */
    @NonNull
    List<String> decodePermissions(@NonNull String permBits, int permVer);
}
