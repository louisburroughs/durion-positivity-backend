package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.VendorResponse;
import com.positivity.accounting.internal.entity.Vendor;
import com.positivity.accounting.internal.enums.VendorStatus;
import com.positivity.accounting.internal.repository.VendorRepository;
import com.positivity.accounting.internal.service.VendorDirectoryServiceImpl;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for VendorDirectoryServiceImpl (Issue #816).
 * Covers typeahead search, single-vendor lookup, and upsert behavior.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VendorDirectoryService Unit Tests")
class VendorDirectoryServiceImplTest {

    private static final UUID VENDOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Mock
    private VendorRepository vendorRepository;

    @InjectMocks
    private VendorDirectoryServiceImpl service;

    private static Vendor vendor(UUID id, String name) {
        Vendor vendor = new Vendor(id, name);
        vendor.setStatus(VendorStatus.ACTIVE);
        return vendor;
    }

    @Nested
    @DisplayName("searchVendors")
    class SearchVendors {

        @Test
        @DisplayName("Should perform case-insensitive contains search ordered by name")
        void shouldSearchByName() {
            when(vendorRepository.findByNameContainingIgnoreCaseOrderByNameAsc(eq("acme"), any(Pageable.class)))
                    .thenReturn(List.of(vendor(VENDOR_ID, "Acme Auto Parts")));

            List<VendorResponse> results = service.searchVendors("acme", 20);

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().getVendorId()).isEqualTo(VENDOR_ID);
            assertThat(results.getFirst().getName()).isEqualTo("Acme Auto Parts");
            assertThat(results.getFirst().getStatus()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("Should trim the search term before matching")
        void shouldTrimSearchTerm() {
            when(vendorRepository.findByNameContainingIgnoreCaseOrderByNameAsc(eq("acme"), any(Pageable.class)))
                    .thenReturn(List.of());

            service.searchVendors("  acme  ", 20);

            verify(vendorRepository).findByNameContainingIgnoreCaseOrderByNameAsc(eq("acme"), any(Pageable.class));
        }

        @Test
        @DisplayName("Should list all vendors when no term is given")
        void shouldListAllWhenTermBlank() {
            when(vendorRepository.findAllByOrderByNameAsc(any(Pageable.class))).thenReturn(List.of());

            service.searchVendors("  ", 20);
            service.searchVendors(null, 20);

            verify(vendorRepository, never())
                    .findByNameContainingIgnoreCaseOrderByNameAsc(any(String.class), any(Pageable.class));
        }

        @Test
        @DisplayName("Should cap requested limit at the server-side maximum")
        void shouldCapLimit() {
            when(vendorRepository.findAllByOrderByNameAsc(any(Pageable.class))).thenReturn(List.of());

            service.searchVendors(null, 5000);

            verify(vendorRepository).findAllByOrderByNameAsc(PageRequest.of(0, VendorDirectoryServiceImpl.MAX_RESULTS));
        }

        @Test
        @DisplayName("Should fall back to the default limit for non-positive limits")
        void shouldDefaultNonPositiveLimit() {
            when(vendorRepository.findAllByOrderByNameAsc(any(Pageable.class))).thenReturn(List.of());

            service.searchVendors(null, 0);

            verify(vendorRepository).findAllByOrderByNameAsc(PageRequest.of(0, 20));
        }
    }

    @Nested
    @DisplayName("getVendorById")
    class GetVendorById {

        @Test
        @DisplayName("Should resolve a vendor by id")
        void shouldResolveVendor() {
            when(vendorRepository.findById(VENDOR_ID)).thenReturn(Optional.of(vendor(VENDOR_ID, "Acme Auto Parts")));

            Optional<VendorResponse> result = service.getVendorById(VENDOR_ID);

            assertThat(result).isPresent();
            assertThat(result.get().getName()).isEqualTo("Acme Auto Parts");
        }

        @Test
        @DisplayName("Should return empty for unknown vendor")
        void shouldReturnEmptyForUnknown() {
            when(vendorRepository.findById(VENDOR_ID)).thenReturn(Optional.empty());

            assertThat(service.getVendorById(VENDOR_ID)).isEmpty();
        }
    }

    @Nested
    @DisplayName("recordVendor")
    class RecordVendor {

        @Test
        @DisplayName("Should insert a new vendor when not present")
        void shouldInsertNewVendor() {
            when(vendorRepository.findById(VENDOR_ID)).thenReturn(Optional.empty());

            service.recordVendor(VENDOR_ID, "Acme Auto Parts");

            ArgumentCaptor<Vendor> captor = ArgumentCaptor.forClass(Vendor.class);
            verify(vendorRepository).save(captor.capture());
            assertThat(captor.getValue().getVendorId()).isEqualTo(VENDOR_ID);
            assertThat(captor.getValue().getName()).isEqualTo("Acme Auto Parts");
            assertThat(captor.getValue().getStatus()).isEqualTo(VendorStatus.ACTIVE);
        }

        @Test
        @DisplayName("Should refresh the stored name when it changed")
        void shouldRefreshChangedName() {
            when(vendorRepository.findById(VENDOR_ID)).thenReturn(Optional.of(vendor(VENDOR_ID, "Old Name")));

            service.recordVendor(VENDOR_ID, "New Name");

            ArgumentCaptor<Vendor> captor = ArgumentCaptor.forClass(Vendor.class);
            verify(vendorRepository).save(captor.capture());
            assertThat(captor.getValue().getName()).isEqualTo("New Name");
        }

        @Test
        @DisplayName("Should not save when the name is unchanged")
        void shouldSkipUnchangedName() {
            when(vendorRepository.findById(VENDOR_ID)).thenReturn(Optional.of(vendor(VENDOR_ID, "Acme Auto Parts")));

            service.recordVendor(VENDOR_ID, "Acme Auto Parts");

            verify(vendorRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should ignore null or blank names")
        void shouldIgnoreBlankNames() {
            service.recordVendor(VENDOR_ID, null);
            service.recordVendor(VENDOR_ID, "   ");

            verify(vendorRepository, never()).findById(any());
            verify(vendorRepository, never()).save(any());
        }
    }
}
