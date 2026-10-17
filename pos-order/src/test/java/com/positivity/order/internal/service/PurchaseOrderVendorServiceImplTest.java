package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.dto.purchaseorder.ListPurchaseOrderVendorsRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderVendorResponse;
import com.positivity.order.internal.entity.ExtSupplierVendor;
import com.positivity.order.internal.enums.PurchaseOrderVendorStatus;
import com.positivity.order.internal.exception.PurchaseOrderRequestValidationException;
import com.positivity.order.internal.repository.ExtSupplierVendorRepository;
import com.positivity.web.common.ReplicationPendingException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/** The vendor read's paging, sorting and mapping rules (CAP:550, #2680); the filter itself is proven on Postgres. */
@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseOrderVendorServiceImpl (#2680)")
class PurchaseOrderVendorServiceImplTest {

    private static final UUID VENDOR_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b06");

    @Mock
    private ExtSupplierVendorRepository vendors;

    @InjectMocks
    private PurchaseOrderVendorServiceImpl service;

    private static ExtSupplierVendor row(ExtSupplierVendor.Status status) {
        return ExtSupplierVendor.builder()
                .vendorId(VENDOR_ID)
                .vendorNumber("V-000123")
                .displayName("Acme Tire Supply")
                .status(status)
                .aggregateVersion(3L)
                .build();
    }

    @Test
    @DisplayName("an unsorted page sorts by displayName and breaks ties on vendorId")
    void unsortedPageDefaultsToDisplayNameWithTieBreaker() {
        Pageable sorted = PurchaseOrderVendorServiceImpl.sortedAndTieBroken(PageRequest.of(1, 10));

        assertThat(sorted.getPageNumber()).isEqualTo(1);
        assertThat(sorted.getPageSize()).isEqualTo(10);
        assertThat(sorted.getSort()).isEqualTo(Sort.by(Sort.Order.asc("displayName"), Sort.Order.asc("vendorId")));
    }

    @Test
    @DisplayName("a caller's sort on a listed column is kept, in its direction, with the tie-breaker appended")
    void callerSortIsKeptWithTieBreaker() {
        Pageable sorted = PurchaseOrderVendorServiceImpl.sortedAndTieBroken(
                PageRequest.of(0, 20, Sort.by(Sort.Order.desc("vendorNumber"), Sort.Order.asc("status"))));

        assertThat(sorted.getSort())
                .isEqualTo(
                        Sort.by(Sort.Order.desc("vendorNumber"), Sort.Order.asc("status"), Sort.Order.asc("vendorId")));
    }

    @Test
    @DisplayName("a sort on any other property is the caller's error (400), not a PropertyReferenceException 500")
    void unknownSortPropertyIsRefused() {
        ListPurchaseOrderVendorsRequest filter = new ListPurchaseOrderVendorsRequest();
        Pageable pageable = PageRequest.of(0, 20, Sort.by("remitTo"));

        assertThatThrownBy(() -> service.listVendors(filter, pageable))
                .isInstanceOf(PurchaseOrderRequestValidationException.class)
                .hasMessageContaining("remitTo")
                .hasMessageContaining("vendorNumber, displayName, status or vendorId");
    }

    @Test
    @DisplayName("a caller echoing the page's own vendorId tie-breaker is accepted, and the tie-breaker is not doubled")
    void echoedTieBreakerIsAccepted() {
        Pageable sorted = PurchaseOrderVendorServiceImpl.sortedAndTieBroken(
                PageRequest.of(1, 20, Sort.by(Sort.Order.asc("displayName"), Sort.Order.asc("vendorId"))));

        assertThat(sorted.getSort()).isEqualTo(Sort.by(Sort.Order.asc("displayName"), Sort.Order.asc("vendorId")));
    }

    @Test
    @DisplayName("status maps explicitly in both directions")
    void statusMapsBothWays() {
        for (PurchaseOrderVendorStatus status : PurchaseOrderVendorStatus.values()) {
            assertThat(PurchaseOrderVendorServiceImpl.toResponse(PurchaseOrderVendorServiceImpl.toEntity(status)))
                    .isEqualTo(status);
        }
    }

    @Test
    @DisplayName("the list maps each copy row to the four-field item and queries with the tie-broken page")
    void listMapsRowsAndPassesTieBrokenPage() {
        when(vendors.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row(ExtSupplierVendor.Status.ACTIVE))));

        var page = service.listVendors(new ListPurchaseOrderVendorsRequest(), PageRequest.of(0, 20));

        assertThat(page.getContent())
                .containsExactly(new PurchaseOrderVendorResponse(
                        VENDOR_ID, "V-000123", "Acme Tire Supply", PurchaseOrderVendorStatus.ACTIVE));
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(vendors).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("vendorId")).isNotNull();
    }

    @Test
    @DisplayName("getVendor returns an INACTIVE vendor as a fact, not a refusal")
    void getVendorReturnsInactiveAsIs() {
        when(vendors.findById(VENDOR_ID)).thenReturn(Optional.of(row(ExtSupplierVendor.Status.INACTIVE)));

        assertThat(service.getVendor(VENDOR_ID).status()).isEqualTo(PurchaseOrderVendorStatus.INACTIVE);
    }

    @Test
    @DisplayName("getVendor on an id the copy lacks is 503 VENDOR_REPLICATION_PENDING, the guard's own code")
    void getVendorOnMissingIdIsReplicationPending() {
        when(vendors.findById(VENDOR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getVendor(VENDOR_ID))
                .isInstanceOfSatisfying(
                        ReplicationPendingException.class,
                        e -> assertThat(e.getCode()).isEqualTo(SupplierVendorGuard.VENDOR_REPLICATION_PENDING));
    }

    @Test
    @DisplayName("LIKE wildcards in q are matched literally")
    void likeWildcardsAreEscaped() {
        assertThat(PurchaseOrderVendorServiceImpl.escapeLike("50%_off\\")).isEqualTo("50\\%\\_off\\\\");
        assertThat(PurchaseOrderVendorServiceImpl.escapeLike("acme")).isEqualTo("acme");
    }
}
