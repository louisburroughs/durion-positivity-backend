package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import com.positivity.securityservice.internal.domain.PermissionBitsetCodec;
import com.positivity.securityservice.internal.enums.PermissionCode;

/**
 * Unit tests for {@link PermissionCatalogVersionServiceImpl}.
 *
 * <p>Verifies PERM-005 acceptance criteria: catalog version reporting,
 * permission count, and permission bitset decode behavior including
 * round-trip fidelity, empty-set handling, and sorted result ordering.
 *
 * Issue: PERM-005
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PermissionCatalogVersionServiceImpl")
class PermissionCatalogVersionServiceImplTest {

    @InjectMocks
    private PermissionCatalogVersionServiceImpl sut;

    // ── getCatalogVersion() ──────────────────────────────────────────────────

    /**
     * PERM-005 AC4: getCatalogVersion() delegates to PermissionCode.CATALOG_VERSION.
     */
    @Test
    @DisplayName("getCatalogVersion() returns PermissionCode.CATALOG_VERSION")
    void getCatalogVersion_returnsPermissionCodeCatalogVersion() {
        int version = sut.getCatalogVersion();

        assertThat(version).isEqualTo(PermissionCode.CATALOG_VERSION);
    }

    // ── getPermissionCount() ─────────────────────────────────────────────────

    /**
     * PERM-005 AC5: getPermissionCount() returns PermissionCode.values().length (215).
     */
    @Test
    @DisplayName("getPermissionCount() returns PermissionCode.values().length")
    void getPermissionCount_returns215() {
        int count = sut.getPermissionCount();

        assertThat(count)
                .isEqualTo(PermissionCode.values().length);
    }

    // ── decodePermissions() ──────────────────────────────────────────────────

    /**
     * PERM-005 AC6: decodePermissions() delegates to PermissionBitsetCodec and returns
     * the correct permission code strings for the encoded bitset.
     */
    @Test
    @DisplayName("decodePermissions() returns decoded permission code strings for encoded bitset")
    void decodePermissions_returnsCorrectPermissionCodes() {
        String encoded = PermissionBitsetCodec.encode(
                Set.of(PermissionCode.ACCOUNTING__JE__VIEW, PermissionCode.ACCOUNTING__JE__CREATE));

        List<String> result = sut.decodePermissions(encoded, PermissionCode.CATALOG_VERSION);

        assertThat(result).contains("accounting:je:view", "accounting:je:create");
    }

    /**
     * PERM-005 AC6: An empty bitset string decodes to an empty list.
     */
    @Test
    @DisplayName("decodePermissions() returns empty list for empty permission bitset")
    void decodePermissions_returnsEmptyListForEmptyBitset() {
        String encoded = PermissionBitsetCodec.encode(Set.of());

        List<String> result = sut.decodePermissions(encoded, PermissionCode.CATALOG_VERSION);

        assertThat(result).isEmpty();
    }

    /**
     * PERM-005 AC6: The result list is sorted alphabetically by permission code string.
     */
    @Test
    @DisplayName("decodePermissions() returns result sorted alphabetically")
    void decodePermissions_returnsListSortedAlphabetically() {
        // Issue PERM-005: encode three permissions whose codes are not naturally ordered
        // by enum declaration — result must still be alphabetically sorted.
        String encoded = PermissionBitsetCodec.encode(
                Set.of(
                        PermissionCode.ACCOUNTING__JE__VIEW,
                        PermissionCode.ACCOUNTING__JE__CREATE,
                        PermissionCode.ACCOUNTING__JE__POST));

        List<String> result = sut.decodePermissions(encoded, PermissionCode.CATALOG_VERSION);

        assertThat(result).isSorted();
    }
}
