package com.positivity.inventory.internal.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.inventory.internal.config.ReservationRequestService;
import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.ReservationStatus;
import com.positivity.inventory.internal.repository.AllocationRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import com.positivity.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drives the reservation-request command's handler against real JPA, which the mock-based
 * {@link com.positivity.inventory.internal.service.ReservationRequestHandlerTest} cannot.
 *
 * <p>Creating the reservation used to set an immutable {@code List.of(allocation)} on the managed
 * entity and save it again; Hibernate's merge clears and refills that collection in place, and
 * {@code List.of} answers with an {@code UnsupportedOperationException}. Every reservation request
 * a promoted workorder issued failed that way, so no reservation and no outcome fact ever existed.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("ReservationRequestHandler against real persistence")
class ReservationRequestHandlerPersistenceTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-000000000001");

    @Autowired
    private ReservationRequestService reservationRequestService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private AllocationRepository allocationRepository;

    @BeforeEach
    void bindTenant() {
        TenantContext.bind(TENANT);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("A workorder line's demand is recorded with its soft allocation, and a shortfall backorders it")
    void recordsReservationAndAllocationForWorkorderLine() {
        UUID workorderLineId = UUID.randomUUID();
        UUID stockItemId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        assertThatCode(() -> reservationRequestService.handle(
                        workorderLineId, null, stockItemId, new BigDecimal("2"), locationId, null))
                .doesNotThrowAnyException();

        ReservationEntity reservation =
                reservationRepository.findByWorkorderLineId(workorderLineId).orElseThrow();
        assertThat(reservation.getStockItemId()).isEqualTo(stockItemId);
        assertThat(reservation.getRequiredQuantity()).isEqualByComparingTo("2");
        // Nothing is on hand at a location nobody stocked, so the whole demand is short.
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.BACKORDERED);
        assertThat(allocationRepository.findByReservation(reservation)).hasSize(1);
    }
}
