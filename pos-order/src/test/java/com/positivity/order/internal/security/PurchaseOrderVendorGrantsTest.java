package com.positivity.order.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The purchase-order vendor read (CAP:550, #2680) is gated by {@code order:purchase_order:view} precisely because the
 * baseline grants give it to every purchase-order persona while none of them holds a vendor-master read. If a seed
 * change ever drops it from one of those roles, the vendor picker goes dark for the people who raise purchase orders.
 */
@DisplayName("Purchase-order personas hold order:purchase_order:view in the alpha seed (#2680)")
class PurchaseOrderVendorGrantsTest {

    /** Surefire runs with the module directory as its working directory. */
    private static final Path ROLE_PERMISSIONS =
            Path.of("..", "scripts", "fixtures", "seed", "alpha", "security", "role-permissions.csv");

    private static final List<String> PURCHASE_ORDER_PERSONAS =
            List.of("INVENTORY_LEAD", "INVENTORY_MANAGER", "LOCATION_MANAGER");

    @Test
    @DisplayName("INVENTORY_LEAD, INVENTORY_MANAGER and LOCATION_MANAGER are granted order:purchase_order:view")
    void purchaseOrderPersonasHoldTheViewGrant() throws IOException {
        Map<String, Set<String>> grants = grants();
        for (String role : PURCHASE_ORDER_PERSONAS) {
            assertThat(grants)
                    .as("role %s is seeded", role)
                    .containsKey(role);
            assertThat(grants.get(role))
                    .as("%s can read the purchase-order vendor copy", role)
                    .contains(PurchaseOrderPermissions.PURCHASE_ORDER_VIEW);
        }
    }

    /** {@code roleName,perm;perm;...}: one row per role. */
    private static Map<String, Set<String>> grants() throws IOException {
        return Files.readAllLines(ROLE_PERMISSIONS).stream()
                .skip(1)
                .filter(line -> !line.isBlank())
                .map(line -> line.split(",", 2))
                .collect(Collectors.toMap(
                        parts -> parts[0].trim(),
                        parts -> parts.length < 2
                                ? Set.of()
                                : Arrays.stream(parts[1].split(";"))
                                        .map(String::trim)
                                        .collect(Collectors.toSet())));
    }
}
