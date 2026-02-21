package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.DeactivateLocationResponse;
import com.positivity.inventory.internal.service.InventoryLocationServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies current deactivation response contract for inventory locations.
 *
 * Issue: CAP-221
 */
class InventoryLocationServiceImplTest {

    @Test
    void deactivateLocation_returnsInactiveResponseWithProvidedLocationIds() {
        InventoryLocationServiceImpl service = new InventoryLocationServiceImpl();
        UUID sourceLocationId = UUID.randomUUID();
        UUID destinationLocationId = UUID.randomUUID();

        DeactivateLocationResponse response = service.deactivateLocation(sourceLocationId, destinationLocationId);

        assertThat(response).isNotNull();
        assertThat(response.getSourceLocationId()).isEqualTo(sourceLocationId);
        assertThat(response.getDestinationLocationId()).isEqualTo(destinationLocationId);
        assertThat(response.getStatus()).isEqualTo("Inactive");
    }
}
