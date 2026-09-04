package com.positivity.supplier.internal.adapter.ediwheelb;

import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.domain.model.SupplierStockSnapshot;
import com.positivity.supplier.internal.spi.SupplierAdapterCodec;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * EDIWheel Stock Report B2.1 codec: {@code GET /B2_1/report} (CAP-322 #1228).
 *
 * <p>Registered under {@code (STOCK_REPORT, EDIWHEEL_B, B2_1)} (ADR-0051 §3). Like every codec it
 * performs no I/O: it produces a transport-free request spec and maps a response body to the
 * canonical snapshot (ADR-0051 §5).
 *
 * <h2>Absence is not zero</h2>
 *
 * A stock report lists what the vendor chose to report. A line with no stated quantity decodes to a
 * null quantity, and an article missing from the document produces no line at all — neither is a
 * statement that the vendor has none. Coercing either to zero would tell a counter user a tyre is
 * unavailable on the strength of a vendor's silence.
 *
 * <h2>The vendor's clock, and ours</h2>
 *
 * {@code issueDate}/{@code issueTime} are the vendor's statement of when the snapshot was taken.
 * They are decoded as stated and kept distinct from the fetch timestamp the import records: a
 * report fetched at noon may describe stock as of 06:00, and staleness is measured against the
 * vendor's figure, not ours.
 */
@Component
@RequiredArgsConstructor
public class EdiwheelB21StockReportCodec implements SupplierAdapterCodec {

    /** Envelope header date: {@code yyyyMMdd} in the norm's examples. */
    private static final DateTimeFormatter VENDOR_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Envelope header time: {@code HHmm} or {@code HHmmss}. */
    private static final DateTimeFormatter VENDOR_TIME_SHORT = DateTimeFormatter.ofPattern("HHmm");

    private static final DateTimeFormatter VENDOR_TIME_LONG = DateTimeFormatter.ofPattern("HHmmss");

    private final ObjectMapper objectMapper;

    @Override
    @NonNull
    public SupplierCapability capability() {
        return SupplierCapability.STOCK_REPORT;
    }

    @Override
    @NonNull
    public ProtocolFamily family() {
        return ProtocolFamily.EDIWHEEL_B;
    }

    @Override
    @NonNull
    public ProtocolVersion version() {
        return ProtocolVersion.B2_1;
    }

    /**
     * Builds the snapshot fetch.
     *
     * <p>B2.1 takes no query parameters in the norm — the buyer is identified by the credential the
     * binding carries. The buyer account is passed here anyway and sent only when the binding's
     * vendor expects it, because the sibling C1.0 report on the same spec does take
     * {@code buyerParty}/{@code agencyCode} and a deployment binding a C1.0 endpoint to this codec
     * would otherwise silently fetch nothing.
     *
     * @param partyContext buyer identity from the profile's billing account
     * @param includeBuyerParty whether to send the buyer identification as query parameters
     * @return a transport-free request spec
     */
    @NonNull
    public SupplierRequestSpec buildRequest(@NonNull PartyContext partyContext, boolean includeBuyerParty) {
        Map<String, String> query = new LinkedHashMap<>();
        if (includeBuyerParty) {
            query.put("buyerParty", partyContext.billingAccountNumber());
            if (partyContext.billingAgencyCode() != null
                    && !partyContext.billingAgencyCode().isBlank()) {
                query.put("agencyCode", partyContext.billingAgencyCode());
            }
        }
        // Idempotent: a stock report is a read of vendor state with no commercial effect, so a
        // retry after transmission costs nothing but a second snapshot (ADR-0052 §5).
        return new SupplierRequestSpec("GET", null, query, null, null, "application/json", true);
    }

    /**
     * Decodes a B2.1 response body into the canonical snapshot.
     *
     * @param body response body as received
     * @return the snapshot, including any lines that could not be decoded
     * @throws StockReportDecodeException when the body is not a B2.1 document, or the vendor
     *                                    signalled a document-level error code
     */
    @NonNull
    public SupplierStockSnapshot decode(@Nullable String body) {
        StockReportB21Wire.StockReport report = parseDocument(body);
        StockReportB21Wire.EnvelopeHeader header = report.envelopeHeader();
        requireNoVendorError(header);

        String documentId = header == null ? null : trimToNull(header.documentId());
        LocalDate issuedOn = header == null ? null : parseDate(header.issueDate());
        Instant issuedAt = issuedOn == null ? null : parseInstant(issuedOn, header.issueTime());

        List<StockReportB21Wire.Line> wireLines = report.lineLevel() == null ? List.of() : report.lineLevel();
        DecodedLines decoded = decodeLines(wireLines);

        return new SupplierStockSnapshot(
                documentId,
                issuedOn,
                issuedAt,
                report.buyerParty() == null ? null : trimToNull(report.buyerParty()),
                decoded.lines(),
                decoded.rejected(),
                wireLines.size());
    }

    /**
     * Parses the raw body into a document, or fails with the specific reason a caller needs to
     * distinguish an empty response from a malformed one from a syntactically valid non-document.
     */
    private StockReportB21Wire.@NonNull StockReport parseDocument(@Nullable String body) {
        if (body == null || body.isBlank()) {
            throw new StockReportDecodeException("Stock report response body was empty");
        }
        StockReportB21Wire.StockReport report;
        try {
            report = objectMapper.readValue(body, StockReportB21Wire.StockReport.class);
        } catch (Exception e) {
            throw new StockReportDecodeException("Stock report response body is not a B2.1 document", e);
        }
        if (report == null) {
            throw new StockReportDecodeException("Stock report response body decoded to no document");
        }
        return report;
    }

    /**
     * A document-level error is a vendor rejection, not an empty warehouse. Treating it as a
     * snapshot would publish "no stock reported" for an entire market.
     */
    private void requireNoVendorError(StockReportB21Wire.@Nullable EnvelopeHeader header) {
        String errorCode = header == null ? null : trimToNull(header.errorCode());
        if (errorCode != null && !"0".equals(errorCode)) {
            throw new StockReportDecodeException("Stock report carries vendor error code " + errorCode);
        }
    }

    /** The reported lines split into what mapped cleanly and what a line-level failure rejected. */
    private record DecodedLines(
            List<SupplierStockSnapshot.Line> lines, List<SupplierStockSnapshot.RejectedLine> rejected) {}

    private DecodedLines decodeLines(List<StockReportB21Wire.Line> wireLines) {
        List<SupplierStockSnapshot.Line> lines = new ArrayList<>(wireLines.size());
        List<SupplierStockSnapshot.RejectedLine> rejected = new ArrayList<>();

        for (StockReportB21Wire.Line wireLine : wireLines) {
            if (wireLine == null) {
                rejected.add(new SupplierStockSnapshot.RejectedLine(null, "null stock report line"));
                continue;
            }
            try {
                lines.add(toLine(wireLine));
            } catch (IllegalArgumentException e) {
                rejected.add(new SupplierStockSnapshot.RejectedLine(trimToNull(wireLine.lineId()), e.getMessage()));
            }
        }
        return new DecodedLines(lines, rejected);
    }

    // The IllegalArgumentException thrown below (and in parseQuantity) is deliberately left as-is
    // (#1694) rather than retyped to a module exception: it is a purely local control-flow signal
    // — decodeLines's catch (IllegalArgumentException e) above always intercepts it and records
    // the line as rejected — and it never escapes this class, let alone reaches a controller.
    // Retyping it to a type that does not extend IllegalArgumentException would silently stop
    // being caught there and fail the whole import instead of rejecting one line.
    private SupplierStockSnapshot.Line toLine(StockReportB21Wire.Line wireLine) {
        StockReportB21Wire.Article article = wireLine.article();
        StockReportB21Wire.ArticleIdentification identity = article == null ? null : article.articleIdentification();
        String ean = identity == null ? null : trimToNull(identity.eanuccarticleID());
        String supplierCode = identity == null ? null : trimToNull(identity.manufacturersArticleId());
        String buyersArticleId = identity == null ? null : trimToNull(identity.buyersArticleId());

        if (ean == null && supplierCode == null && buyersArticleId == null) {
            throw new IllegalArgumentException("line states no article identity");
        }

        String description = article == null || article.articleDescription() == null
                ? null
                : trimToNull(article.articleDescription().articleDescriptionText());

        Integer quantity = article == null || article.availableQuantity() == null
                ? null
                : parseQuantity(article.availableQuantity().quantityValue());

        return new SupplierStockSnapshot.Line(
                trimToNull(wireLine.lineId()), ean, supplierCode, buyersArticleId, description, quantity);
    }

    /**
     * Parses a reported quantity. A blank or absent value yields null — the vendor stated no
     * quantity, which the caller must not read as zero.
     */
    @Nullable
    private Integer parseQuantity(@Nullable String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            // Some markets state whole quantities with a decimal part ("12.000"); take the integral
            // part rather than rejecting a line that is perfectly well formed for its market.
            return new BigDecimal(value.replace(',', '.')).intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("quantityValue is not a whole number: '" + value + "'");
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("quantityValue is not a number: '" + value + "'");
        }
    }

    @Nullable
    private LocalDate parseDate(@Nullable String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value, VENDOR_DATE);
        } catch (Exception e) {
            try {
                return LocalDate.parse(value);
            } catch (Exception alsoIgnored) {
                // A header date that will not parse is descriptive only; the import's own fetch
                // timestamp still bounds the snapshot, so this must not fail a whole document.
                return null;
            }
        }
    }

    @Nullable
    private Instant parseInstant(LocalDate date, @Nullable String rawTime) {
        String value = trimToNull(rawTime);
        if (value == null) {
            return null;
        }
        LocalTime time;
        try {
            time = LocalTime.parse(value, value.length() > 4 ? VENDOR_TIME_LONG : VENDOR_TIME_SHORT);
        } catch (Exception e) {
            return null;
        }
        // The norm states no zone. UTC is assumed and the assumption is recorded here rather than
        // hidden: the alternative — the server's zone — would make the same document decode
        // differently depending on where it was processed.
        return LocalDateTime.of(date, time).toInstant(ZoneOffset.UTC);
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
