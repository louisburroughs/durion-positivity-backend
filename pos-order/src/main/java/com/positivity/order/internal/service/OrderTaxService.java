package com.positivity.order.internal.service;

import com.positivity.order.internal.client.TaxPort;
import com.positivity.order.internal.entity.ExtLocation;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderLine;
import com.positivity.order.internal.exception.TaxUnavailableException;
import com.positivity.order.internal.repository.ExtLocationRepository;
import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxLineItem;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Batch tax recompute for an order (parity story B3, spec R3.4): jurisdiction address from the
 * {@code ext_location} replica (pos-workorder pattern), calculation by pos-tax, per-line tax and
 * order taxTotal persisted on the aggregate. Called at quote (story A3) and checkout (story C1) —
 * never per line mutation.
 *
 * @throws TaxUnavailableException when the address cannot be resolved or pos-tax is unreachable —
 *     quotes/checkout must not complete on stale or absent tax
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderTaxService {

    private final TaxPort taxPort;
    private final ExtLocationRepository extLocationRepository;
    private final OrderTotalsCalculator totalsCalculator;

    public void recomputeTax(@NonNull SalesOrder order) {
        List<SalesOrderLine> lines =
                order.getLines().stream().filter(Objects::nonNull).toList();
        if (lines.isEmpty()) {
            order.setTaxTotal(BigDecimal.ZERO);
            order.setTaxStale(false);
            totalsCalculator.recalculate(order);
            return;
        }

        // Line subtotals must be current before taxing the discounted base.
        totalsCalculator.recalculate(order);

        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(lines.stream().map(OrderTaxService::toTaxLineItem).toList())
                .destinationAddress(resolveAddress(order))
                .currencyCode("USD")
                .customerId(
                        order.getCustomerId() != null ? order.getCustomerId().toString() : null)
                .build();

        TaxCalculationResponse response = taxPort.calculate(request)
                .orElseThrow(() -> new TaxUnavailableException(
                        "Tax service unavailable; cannot finalize totals for order " + order.getOrderId()));

        Map<String, SalesOrderLine> byId =
                lines.stream().collect(Collectors.toMap(l -> l.getOrderLineId().toString(), Function.identity()));
        if (response.getLineItemTaxes() != null) {
            response.getLineItemTaxes().forEach(lineTax -> {
                SalesOrderLine line = byId.get(lineTax.getLineItemId());
                if (line != null && lineTax.getTaxAmount() != null) {
                    line.setTaxAmount(lineTax.getTaxAmount());
                }
            });
        }
        order.setTaxStale(false);
        totalsCalculator.recalculate(order);
    }

    private TaxCalculationRequest.TaxAddress resolveAddress(SalesOrder order) {
        if (order.getLocationId() == null) {
            throw new TaxUnavailableException("Order " + order.getOrderId() + " has no location for tax jurisdiction");
        }
        ExtLocation location = extLocationRepository
                .findById(order.getLocationId())
                .orElseThrow(() -> new TaxUnavailableException(
                        "No location replica row for " + order.getLocationId() + "; cannot resolve tax jurisdiction"));
        if (location.getPostalCode() == null || location.getCountry() == null) {
            throw new TaxUnavailableException(
                    "Location replica " + order.getLocationId() + " lacks postalCode/country for tax jurisdiction");
        }
        return TaxCalculationRequest.TaxAddress.builder()
                .countryCode(location.getCountry())
                .regionCode(location.getRegion())
                .city(location.getCity())
                .postalCode(location.getPostalCode())
                .line1(location.getAddressLine1())
                .line2(location.getAddressLine2())
                .build();
    }

    private static TaxLineItem toTaxLineItem(SalesOrderLine line) {
        return TaxLineItem.builder()
                .lineItemId(line.getOrderLineId().toString())
                .description(line.getItemDescription())
                .quantity(BigDecimal.valueOf(line.getQuantity()))
                .unitPrice(line.getUnitPrice())
                .subtotal(line.getLineSubtotal())
                .build();
    }
}
