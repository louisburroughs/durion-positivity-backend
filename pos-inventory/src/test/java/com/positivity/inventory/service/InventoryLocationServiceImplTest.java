package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.client.StorageLocationValidationClient;
import com.positivity.inventory.internal.dto.DeactivateLocationResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.service.InventoryLocationServiceImpl;
import com.positivity.security.common.GatewaySecurityConstants;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;

/**
 * Verifies current deactivation response contract for inventory locations.
 *
 * Issue: CAP-221
 */
class InventoryLocationServiceImplTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void deactivateLocation_returnsInactiveResponseWithProvidedLocationIds() {
        UUID sourceLocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID destinationLocationId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID siteId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        List<InventoryLedgerEntry> persistedEntries = new ArrayList<>();
        List<Object> publishedEvents = new ArrayList<>();
        Map<String, StorageLocationValidationClient.StorageLocationValidation> validations = new HashMap<>();
        validations.put(sourceLocationId.toString(), validation(sourceLocationId, siteId, true, true));

        InventoryLocationServiceImpl service = new InventoryLocationServiceImpl(
                stubLedgerRepository(List.of(), Map.of(), persistedEntries),
                new StubStorageLocationValidationClient(validations),
                eventPublisher(publishedEvents),
                new SimpleMeterRegistry(),
                TEST_CLOCK);

        DeactivateLocationResponse response = service.deactivateLocation(sourceLocationId, destinationLocationId);

        assertThat(response).isNotNull();
        assertThat(response.getSourceLocationId()).isEqualTo(sourceLocationId);
        assertThat(response.getDestinationLocationId()).isEqualTo(destinationLocationId);
        assertThat(response.getStatus()).isEqualTo("Inactive");
        assertThat(response.getTransfer()).isNull();
        assertThat(persistedEntries).isEmpty();
        assertThat(publishedEvents).hasSize(1);
    }

    @Test
    void deactivateLocation_withStock_transfersAndReturnsTransferDetails() {
        var authentication = new UsernamePasswordAuthenticationToken("inventory-test-user", "n/a", List.of());
        authentication.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "inventory-test-user"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            UUID sourceLocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID destinationLocationId = UUID.fromString("00000000-0000-0000-0000-000000000002");
            UUID siteId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            List<InventoryLedgerEntry> persistedEntries = new ArrayList<>();
            Map<String, StorageLocationValidationClient.StorageLocationValidation> validations = new HashMap<>();
            validations.put(sourceLocationId.toString(), validation(sourceLocationId, siteId, true, true));
            validations.put(destinationLocationId.toString(), validation(destinationLocationId, siteId, true, true));
            InventoryLocationServiceImpl service = new InventoryLocationServiceImpl(
                    stubLedgerRepository(
                            List.of(onHand("SKU-001", 5L)),
                            Map.of(stockKey("SKU-001", destinationLocationId), 3),
                            persistedEntries),
                    new StubStorageLocationValidationClient(validations),
                    eventPublisher(new ArrayList<>()),
                    new SimpleMeterRegistry(),
                    TEST_CLOCK);

            DeactivateLocationResponse response = service.deactivateLocation(sourceLocationId, destinationLocationId);

            assertThat(response.getTransfer()).isNotNull();
            assertThat(response.getTransfer().getMovedItems()).hasSize(1);
            assertThat(response.getTransfer().getMovedItems().get(0).getItemId())
                    .isEqualTo("SKU-001");
            assertThat(response.getTransfer().getMovedItems().get(0).getQuantity())
                    .isEqualTo(5);
            assertThat(persistedEntries).hasSize(2);
            assertThat(persistedEntries.get(0).getEventType()).isEqualTo(InventoryLedgerEventType.TRANSFER_OUT);
            assertThat(persistedEntries.get(1).getEventType()).isEqualTo(InventoryLedgerEventType.TRANSFER_IN);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private InventoryLedgerEntryRepository stubLedgerRepository(
            List<InventoryLedgerEntryRepository.LocationOnHand> sourceRows,
            Map<String, Integer> destinationOnHand,
            List<InventoryLedgerEntry> persistedEntries) {
        return (InventoryLedgerEntryRepository) Proxy.newProxyInstance(
                InventoryLedgerEntryRepository.class.getClassLoader(),
                new Class[] {InventoryLedgerEntryRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findPositiveOnHandByLocation" -> sourceRows;
                    case "calculateOnHandQuantityAtLocation" ->
                        destinationOnHand.getOrDefault(stockKey((String) args[0], (UUID) args[1]), 0);
                    case "saveAll" -> {
                        for (Object entry : (Iterable<?>) args[0]) {
                            persistedEntries.add((InventoryLedgerEntry) entry);
                        }
                        yield args[0];
                    }
                    case "toString" -> "InventoryLedgerEntryRepositoryStub";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private ApplicationEventPublisher eventPublisher(List<Object> events) {
        return events::add;
    }

    private InventoryLedgerEntryRepository.LocationOnHand onHand(String stockItemId, long onHandQuantity) {
        return new InventoryLedgerEntryRepository.LocationOnHand() {
            @Override
            public String getStockItemId() {
                return stockItemId;
            }

            @Override
            public Long getOnHandQuantity() {
                return onHandQuantity;
            }
        };
    }

    private String stockKey(String stockItemId, UUID locationId) {
        return stockItemId + "|" + locationId;
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    private StorageLocationValidationClient.StorageLocationValidation validation(
            UUID locationId, UUID siteId, boolean exists, boolean active) {
        StorageLocationValidationClient.StorageLocationValidation validation =
                new StorageLocationValidationClient.StorageLocationValidation();
        validation.setStorageLocationId(locationId);
        validation.setSiteId(siteId);
        validation.setExists(exists);
        validation.setActive(active);
        return validation;
    }

    private static final class StubStorageLocationValidationClient extends StorageLocationValidationClient {
        private final Map<String, StorageLocationValidation> validationsById;

        private StubStorageLocationValidationClient(Map<String, StorageLocationValidation> validationsById) {
            super(RestClient.builder(), "http://localhost:8080");
            this.validationsById = validationsById;
        }

        @Override
        public StorageLocationValidation getStorageLocationValidation(String storageLocationId) {
            StorageLocationValidation validation = validationsById.get(storageLocationId);
            if (validation != null) {
                return validation;
            }

            StorageLocationValidation missing = new StorageLocationValidation();
            missing.setExists(false);
            missing.setActive(false);
            missing.setStorageLocationId(UUID.fromString(storageLocationId));
            return missing;
        }
    }
}
