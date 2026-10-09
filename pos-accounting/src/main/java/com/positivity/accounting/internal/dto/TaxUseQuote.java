package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The self-assessed (use) tax quote pos-accounting asks pos-tax for at a vendor bill's approval ({@code POST
 * /v1/tax/calculate}, {@code calculationType = USE}; CAP:550 S43, AW44): the request it sends and the part of the
 * answer it reads. Never served by an accounting endpoint.
 */
public final class TaxUseQuote {

    private TaxUseQuote() {}

    /**
     * The request: one line per qualifying expense line at its net, the ledger currency (ADR-0067 PC-11 (a)), the
     * entry's posting date, the bill as reference, never committable.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(
            List<Line> lineItems,
            Address destinationAddress,
            String currencyCode,
            String calculationType,
            String transactionDate,
            UUID referenceId,
            boolean committable) {}

    /** One priced line: its id (the bill line number, or {@code "1"} for a bill without lines) and its net. */
    public record Line(String lineItemId, String description, BigDecimal quantity, BigDecimal unitPrice) {}

    /** The configured purchase place: the tax country, its region (optional) and postal code. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Address(String countryCode, @Nullable String regionCode, String postalCode) {}

    /** The answer: the tax of each line, as pos-tax returned it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(
            @Nullable BigDecimal totalTax, @Nullable List<LineTax> lineItemTaxes) {}

    /** One line's tax. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineTax(
            @Nullable String lineItemId, @Nullable BigDecimal taxAmount) {}
}
