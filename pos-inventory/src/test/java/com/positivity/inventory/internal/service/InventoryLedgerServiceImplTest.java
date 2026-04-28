package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.InventoryLedgerEntryDto;
import com.positivity.inventory.internal.dto.InventoryLedgerFilterParams;
import com.positivity.inventory.internal.dto.LedgerPage;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class InventoryLedgerServiceImplTest {

    @Mock
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    private InventoryLedgerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new InventoryLedgerServiceImpl(inventoryLedgerEntryRepository);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private InventoryLedgerEntry buildEntry(UUID id) {
        return InventoryLedgerEntry.builder()
                .ledgerEntryId(id)
                .stockItemId("SKU-TEST")
                .eventType(InventoryLedgerEventType.ADJUSTMENT_IN)
                .changeInQuantity(1)
                .quantityAfter(10)
                .transactionUserId("user-1")
                .build();
    }

    private List<InventoryLedgerEntry> buildEntries(int count) {
        List<InventoryLedgerEntry> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(buildEntry(UUID.randomUUID()));
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private void stubFindAll(List<InventoryLedgerEntry> entries) {
        when(inventoryLedgerEntryRepository.findAll(
                any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(entries));
    }

    // -------------------------------------------------------------------------
    // T-002: Exception routing tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("getLedgerEntry")
    class GetLedgerEntry {

        @Test
        @DisplayName("T-002: throws ResourceNotFoundException when entry does not exist")
        void throwsResourceNotFoundExceptionForMissingEntry() {
            UUID missingId = UUID.randomUUID();
            when(inventoryLedgerEntryRepository.findById(missingId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getLedgerEntry(missingId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(missingId.toString());
        }

        @Test
        @DisplayName("T-012: returns mapped DTO for existing entry (happy path)")
        void returnsMappedDtoForExistingEntry() {
            UUID id = UUID.randomUUID();
            InventoryLedgerEntry entry = buildEntry(id);
            when(inventoryLedgerEntryRepository.findById(id)).thenReturn(Optional.of(entry));

            InventoryLedgerEntryDto dto = service.getLedgerEntry(id);

            assertThat(dto.getLedgerEntryId()).isEqualTo(id);
            assertThat(dto.getStockItemId()).isEqualTo("SKU-TEST");
            assertThat(dto.getEventType()).isEqualTo(InventoryLedgerEventType.ADJUSTMENT_IN);
        }
    }

    @Nested
    @DisplayName("listLedgerEntries – movementType/pageToken routing")
    class MovementTypeAndPageToken {

        @Test
        @DisplayName("T-002: throws IllegalArgumentException for unknown movementType string")
        void throwsIllegalArgumentExceptionForUnknownMovementType() {
            InventoryLedgerFilterParams params = InventoryLedgerFilterParams.builder()
                    .movementTypes(List.of("NOT_A_REAL_TYPE"))
                    .pageSize(10)
                    .build();

            // movementTypes are parsed eagerly inside buildSpecification before findAll
            // is called, so no repo stub is needed.
            assertThatThrownBy(() -> service.listLedgerEntries(params))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("NOT_A_REAL_TYPE");
        }

        @Test
        @DisplayName("T-002: throws IllegalArgumentException for corrupt/invalid base64 pageToken")
        void throwsIllegalArgumentExceptionForCorruptPageToken() {
            InventoryLedgerFilterParams params = InventoryLedgerFilterParams.builder()
                    .pageToken("!!!not-valid-base64!!!")
                    .pageSize(10)
                    .build();

            assertThatThrownBy(() -> service.listLedgerEntries(params))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid pageToken");
        }

        @Test
        @DisplayName("T-002: throws IllegalArgumentException for base64 pageToken that is not a valid UUID")
        void throwsIllegalArgumentExceptionForNonUuidPageToken() {
            // Encode a non-UUID string as a valid base64 token
            String badToken = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("not-a-uuid".getBytes(StandardCharsets.UTF_8));
            InventoryLedgerFilterParams params = InventoryLedgerFilterParams.builder()
                    .pageToken(badToken)
                    .pageSize(10)
                    .build();

            assertThatThrownBy(() -> service.listLedgerEntries(params))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid pageToken");
        }
    }

    // -------------------------------------------------------------------------
    // T-010: Next-page token off-by-one fix tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("listLedgerEntries – nextPageToken logic (T-010)")
    class NextPageTokenLogic {

        @Test
        @DisplayName("repo returns exactly pageSize entries → nextPageToken is null (no spurious token)")
        void noNextPageTokenWhenResultCountEqualsPageSize() {
            int pageSize = 5;
            List<InventoryLedgerEntry> entries = buildEntries(pageSize); // exactly 5, repo asked for 6
            stubFindAll(entries);

            InventoryLedgerFilterParams params = InventoryLedgerFilterParams.builder()
                    .pageSize(pageSize)
                    .build();

            LedgerPage<InventoryLedgerEntryDto> page = service.listLedgerEntries(params);

            assertThat(page.getNextPageToken()).isNull();
            assertThat(page.getEntries()).hasSize(pageSize);
        }

        @Test
        @DisplayName("repo returns pageSize+1 entries → nextPageToken is set and result is truncated to pageSize")
        void hasNextPageTokenAndTruncatesWhenRepoReturnsExtraEntry() {
            int pageSize = 5;
            List<InventoryLedgerEntry> entries = buildEntries(pageSize + 1); // 6 returned from repo
            stubFindAll(entries);

            InventoryLedgerFilterParams params = InventoryLedgerFilterParams.builder()
                    .pageSize(pageSize)
                    .build();

            LedgerPage<InventoryLedgerEntryDto> page = service.listLedgerEntries(params);

            assertThat(page.getNextPageToken()).isNotNull();
            assertThat(page.getEntries()).hasSize(pageSize);
        }

        @Test
        @DisplayName("repo returns fewer than pageSize entries → nextPageToken is null")
        void noNextPageTokenWhenResultCountBelowPageSize() {
            int pageSize = 10;
            List<InventoryLedgerEntry> entries = buildEntries(3);
            stubFindAll(entries);

            InventoryLedgerFilterParams params = InventoryLedgerFilterParams.builder()
                    .pageSize(pageSize)
                    .build();

            LedgerPage<InventoryLedgerEntryDto> page = service.listLedgerEntries(params);

            assertThat(page.getNextPageToken()).isNull();
            assertThat(page.getEntries()).hasSize(3);
        }

        @Test
        @DisplayName("repo returns empty list → nextPageToken is null and entries are empty")
        void noNextPageTokenWhenResultIsEmpty() {
            int pageSize = 5;
            stubFindAll(List.of());

            InventoryLedgerFilterParams params = InventoryLedgerFilterParams.builder()
                    .pageSize(pageSize)
                    .build();

            LedgerPage<InventoryLedgerEntryDto> page = service.listLedgerEntries(params);

            assertThat(page.getNextPageToken()).isNull();
            assertThat(page.getEntries()).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // T-012: Additional coverage for listLedgerEntries
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("listLedgerEntries – paging and DTO mapping (T-012)")
    class ListLedgerEntriesCoverage {

        @Test
        @DisplayName("maps returned entities to DTOs preserving stockItemId and eventType")
        void mapsDtoFieldsCorrectly() {
            UUID entryId = UUID.randomUUID();
            InventoryLedgerEntry entry = buildEntry(entryId);
            stubFindAll(List.of(entry));

            InventoryLedgerFilterParams params = InventoryLedgerFilterParams.builder()
                    .pageSize(5)
                    .build();

            LedgerPage<InventoryLedgerEntryDto> page = service.listLedgerEntries(params);

            assertThat(page.getEntries()).hasSize(1);
            InventoryLedgerEntryDto dto = page.getEntries().getFirst();
            assertThat(dto.getLedgerEntryId()).isEqualTo(entryId);
            assertThat(dto.getStockItemId()).isEqualTo("SKU-TEST");
            assertThat(dto.getEventType()).isEqualTo(InventoryLedgerEventType.ADJUSTMENT_IN);
        }

        @Test
        @DisplayName("uses default pageSize of 50 when pageSize param is 0")
        void usesDefaultPageSizeWhenParamIsZero() {
            stubFindAll(List.of());

            InventoryLedgerFilterParams params = InventoryLedgerFilterParams.builder()
                    .pageSize(0)
                    .build();

            LedgerPage<InventoryLedgerEntryDto> page = service.listLedgerEntries(params);

            assertThat(page.getEntries()).isEmpty();
            assertThat(page.getNextPageToken()).isNull();
        }
    }
}
