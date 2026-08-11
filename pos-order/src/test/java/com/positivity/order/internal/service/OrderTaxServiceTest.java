package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.client.TaxPort;
import com.positivity.order.internal.entity.ExtLocation;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderLine;
import com.positivity.order.internal.exception.TaxUnavailableException;
import com.positivity.order.internal.repository.ExtLocationRepository;
import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link OrderTaxService} (parity story B3, spec R3.4).
 *
 * <p>
 * The governing rule is that a quote or checkout must never complete on absent
 * or stale tax, so every way the calculation can fail to produce a real number
 * — no location on the order, no replica row for it, a replica row missing the
 * postal code or country, or pos-tax being unreachable — raises
 * {@link TaxUnavailableException} rather than defaulting to zero. A zero default
 * would be indistinguishable from a genuinely untaxed order and would undercharge
 * the customer.
 *
 * <p>
 * The other detail worth pinning is the ordering: line subtotals are recomputed
 * <em>before</em> the tax call, because tax is charged on the discounted base,
 * and again afterwards to roll the per-line tax into the order total.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderTaxService — jurisdiction resolution and batch recompute")
class OrderTaxServiceTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000091");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000092");
    private static final UUID LINE_ID = UUID.fromString("00000000-0000-0000-0000-000000000093");
    private static final UUID OTHER_LINE_ID = UUID.fromString("00000000-0000-0000-0000-000000000094");

    @Mock
    private TaxPort taxPort;

    @Mock
    private ExtLocationRepository extLocationRepository;

    @Mock
    private OrderTotalsCalculator totalsCalculator;

    private OrderTaxService service;

    @BeforeEach
    void setUp() {
        service = new OrderTaxService(taxPort, extLocationRepository, totalsCalculator);
    }

    private static ExtLocation location(String postalCode, String country) {
        ExtLocation location = new ExtLocation();
        location.setLocationId(LOCATION_ID);
        location.setAddressLine1("1 Main St");
        location.setCity("Austin");
        location.setRegion("TX");
        location.setPostalCode(postalCode);
        location.setCountry(country);
        return location;
    }

    private static SalesOrderLine line(UUID lineId) {
        return SalesOrderLine.builder()
                .orderLineId(lineId)
                .itemSku("BRK-100")
                .itemDescription("Brake pad")
                .quantity(2)
                .unitPrice(new BigDecimal("45.50"))
                .lineSubtotal(new BigDecimal("90.00"))
                .taxAmount(BigDecimal.ZERO)
                .build();
    }

    private static SalesOrder order(UUID locationId, SalesOrderLine... lines) {
        return SalesOrder.builder()
                .orderId(ORDER_ID)
                .locationId(locationId)
                .customerId(UUID.randomUUID())
                .taxStale(true)
                .lines(new ArrayList<>(Arrays.asList(lines)))
                .build();
    }

    private static TaxCalculationResponse.LineItemTax lineTax(UUID lineId, String amount) {
        TaxCalculationResponse.LineItemTax tax = new TaxCalculationResponse.LineItemTax();
        tax.setLineItemId(lineId.toString());
        tax.setTaxAmount(amount == null ? null : new BigDecimal(amount));
        return tax;
    }

    @Test
    @DisplayName("applies each line's tax and clears the stale flag")
    void appliesPerLineTax() {
        SalesOrderLine first = line(LINE_ID);
        SalesOrderLine second = line(OTHER_LINE_ID);
        SalesOrder order = order(LOCATION_ID, first, second);
        when(extLocationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location("78701", "US")));
        TaxCalculationResponse response = new TaxCalculationResponse();
        response.setLineItemTaxes(List.of(lineTax(LINE_ID, "7.42"), lineTax(OTHER_LINE_ID, "3.10")));
        when(taxPort.calculate(any())).thenReturn(Optional.of(response));

        service.recomputeTax(order);

        assertThat(first.getTaxAmount()).isEqualByComparingTo("7.42");
        assertThat(second.getTaxAmount()).isEqualByComparingTo("3.10");
        assertThat(order.isTaxStale()).isFalse();
        // Once before the call so tax is charged on the current discounted base, once after so the
        // per-line tax rolls into the order total.
        verify(totalsCalculator, org.mockito.Mockito.times(2)).recalculate(order);
    }

    @Test
    @DisplayName("sends the replica's address as the tax jurisdiction")
    void sendsReplicaAddress() {
        SalesOrder order = order(LOCATION_ID, line(LINE_ID));
        when(extLocationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location("78701", "US")));
        when(taxPort.calculate(any())).thenReturn(Optional.of(new TaxCalculationResponse()));

        service.recomputeTax(order);

        ArgumentCaptor<TaxCalculationRequest> captor = ArgumentCaptor.forClass(TaxCalculationRequest.class);
        verify(taxPort).calculate(captor.capture());
        TaxCalculationRequest request = captor.getValue();
        assertThat(request.getCurrencyCode()).isEqualTo("USD");
        assertThat(request.getDestinationAddress().getPostalCode()).isEqualTo("78701");
        assertThat(request.getDestinationAddress().getCountryCode()).isEqualTo("US");
        assertThat(request.getDestinationAddress().getRegionCode()).isEqualTo("TX");
        assertThat(request.getLineItems()).singleElement().satisfies(item -> {
            assertThat(item.getLineItemId()).isEqualTo(LINE_ID.toString());
            assertThat(item.getQuantity()).isEqualByComparingTo("2");
            assertThat(item.getSubtotal()).isEqualByComparingTo("90.00");
        });
    }

    @Test
    @DisplayName("zeroes tax on an empty order without calling pos-tax")
    void emptyOrderSkipsTheTaxCall() {
        SalesOrder order = order(LOCATION_ID);

        service.recomputeTax(order);

        assertThat(order.getTaxTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(order.isTaxStale()).isFalse();
        verifyNoInteractions(taxPort, extLocationRepository);
        verify(totalsCalculator).recalculate(order);
    }

    @Test
    @DisplayName("ignores a null line rather than failing the recompute")
    void nullLineIsSkipped() {
        SalesOrder order = order(LOCATION_ID, line(LINE_ID));
        order.getLines().add(null);
        when(extLocationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location("78701", "US")));
        when(taxPort.calculate(any())).thenReturn(Optional.of(new TaxCalculationResponse()));

        service.recomputeTax(order);

        ArgumentCaptor<TaxCalculationRequest> captor = ArgumentCaptor.forClass(TaxCalculationRequest.class);
        verify(taxPort).calculate(captor.capture());
        assertThat(captor.getValue().getLineItems()).hasSize(1);
    }

    @Test
    @DisplayName("leaves a line untouched when the response carries no amount for it")
    void unmatchedOrAmountlessLineTaxIsIgnored() {
        SalesOrderLine only = line(LINE_ID);
        only.setTaxAmount(new BigDecimal("1.11"));
        SalesOrder order = order(LOCATION_ID, only);
        when(extLocationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location("78701", "US")));
        TaxCalculationResponse response = new TaxCalculationResponse();
        // One entry for a line that is not on this order, one for a line with no amount.
        response.setLineItemTaxes(List.of(lineTax(OTHER_LINE_ID, "9.99"), lineTax(LINE_ID, null)));
        when(taxPort.calculate(any())).thenReturn(Optional.of(response));

        service.recomputeTax(order);

        assertThat(only.getTaxAmount()).isEqualByComparingTo("1.11");
        assertThat(order.isTaxStale()).isFalse();
    }

    @Test
    @DisplayName("refuses to finalize when pos-tax returns nothing")
    void unreachableTaxServiceIsFatal() {
        SalesOrder order = order(LOCATION_ID, line(LINE_ID));
        when(extLocationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location("78701", "US")));
        when(taxPort.calculate(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recomputeTax(order))
                .isInstanceOf(TaxUnavailableException.class)
                .hasMessageContaining(ORDER_ID.toString());

        // Left stale on purpose: a zero default would read as a genuinely untaxed order.
        assertThat(order.isTaxStale()).isTrue();
    }

    @Test
    @DisplayName("refuses to finalize an order with no location")
    void missingLocationIsFatal() {
        SalesOrder order = order(null, line(LINE_ID));

        assertThatThrownBy(() -> service.recomputeTax(order))
                .isInstanceOf(TaxUnavailableException.class)
                .hasMessageContaining("no location");

        verify(taxPort, never()).calculate(any());
    }

    @Test
    @DisplayName("refuses to finalize when the location replica has not arrived yet")
    void missingReplicaRowIsFatal() {
        SalesOrder order = order(LOCATION_ID, line(LINE_ID));
        when(extLocationRepository.findById(LOCATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recomputeTax(order))
                .isInstanceOf(TaxUnavailableException.class)
                .hasMessageContaining("No location replica row");

        verify(taxPort, never()).calculate(any());
    }

    @Test
    @DisplayName("refuses to finalize when the replica lacks the fields a jurisdiction needs")
    void incompleteReplicaAddressIsFatal() {
        SalesOrder order = order(LOCATION_ID, line(LINE_ID));
        when(extLocationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location(null, "US")));

        assertThatThrownBy(() -> service.recomputeTax(order))
                .isInstanceOf(TaxUnavailableException.class)
                .hasMessageContaining("postalCode/country");

        when(extLocationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location("78701", null)));

        assertThatThrownBy(() -> service.recomputeTax(order)).isInstanceOf(TaxUnavailableException.class);

        verify(taxPort, never()).calculate(any());
    }
}
