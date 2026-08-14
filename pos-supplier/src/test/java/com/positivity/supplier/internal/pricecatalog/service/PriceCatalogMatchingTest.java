package com.positivity.supplier.internal.pricecatalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.adapter.ediwheelb.PricatDocument;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.domain.model.SupplierPriceCatalogEntry;
import com.positivity.supplier.internal.enums.PriceCatalogMatchMethod;
import com.positivity.supplier.internal.enums.UnmatchedLineReason;
import com.positivity.supplier.internal.pricecatalog.service.PriceCatalogImporter.PreparedImport;
import com.positivity.supplier.internal.pricecatalog.service.ProductCodeResolver.Resolution;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PRICAT line matching and deduplication (#1224, ADR-0053 §5)")
class PriceCatalogMatchingTest {

    private static final UUID MANIFEST_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private SupplierProfileResolver profileResolver;

    @Mock
    private AdapterRegistry adapterRegistry;

    @Mock
    private SupplierBaseClient baseClient;

    @Mock
    private ProductCodeResolver productCodeResolver;

    @Mock
    private PriceCatalogStagingWriter stagingWriter;

    private PriceCatalogImporter service() {
        return new PriceCatalogImporter(
                profileResolver, adapterRegistry, baseClient, productCodeResolver, stagingWriter, CLOCK);
    }

    private static SupplierPriceCatalogEntry entry(String ean, String xref, String supplierCode, int position) {
        return new SupplierPriceCatalogEntry(
                ean,
                supplierCode,
                xref,
                null,
                new BigDecimal("100.00"),
                new BigDecimal("90.00"),
                null,
                null,
                LocalDate.of(2026, 1, 1),
                "SE",
                "SEK",
                "DOC-1",
                LocalDate.of(2026, 1, 1),
                MANIFEST_ID,
                position);
    }

    private static PricatDocument document(List<SupplierPriceCatalogEntry> entries) {
        return new PricatDocument("DOC-1", LocalDate.of(2026, 1, 1), "SE", "SEK", entries, List.of(), entries.size());
    }

    @Test
    void matchesOnExactEanFirst() {
        when(productCodeResolver.resolve("EAN", "3528709999083")).thenReturn(new Resolution.Matched(PRODUCT_ID));

        PreparedImport prepared =
                service().prepare(document(List.of(entry("3528709999083", "0123456789012", "999908", 1))), MANIFEST_ID);

        assertThat(prepared.matched()).singleElement().satisfies(line -> {
            assertThat(line.productId()).isEqualTo(PRODUCT_ID);
            assertThat(line.method()).isEqualTo(PriceCatalogMatchMethod.EAN);
        });
        verify(productCodeResolver, never()).resolve(eq("UPC"), any());
    }

    @Test
    void fallsBackToTheCrossReferenceCodeAsAUpc() {
        when(productCodeResolver.resolve("EAN", "3528709999083")).thenReturn(new Resolution.NotFound());
        when(productCodeResolver.resolve("UPC", "0123456789012")).thenReturn(new Resolution.Matched(PRODUCT_ID));

        PreparedImport prepared =
                service().prepare(document(List.of(entry("3528709999083", "0123456789012", "999908", 1))), MANIFEST_ID);

        assertThat(prepared.matched())
                .singleElement()
                .satisfies(line -> assertThat(line.method()).isEqualTo(PriceCatalogMatchMethod.UPC_XREFERENCE));
    }

    @Test
    void neverMatchesOnTheSupplierCode() {
        when(productCodeResolver.resolve("EAN", "3528709999083")).thenReturn(new Resolution.NotFound());

        PreparedImport prepared =
                service().prepare(document(List.of(entry("3528709999083", null, "999908", 1))), MANIFEST_ID);

        assertThat(prepared.matched()).isEmpty();
        verify(productCodeResolver, never()).resolve(any(), eq("999908"));
    }

    @Test
    void quarantinesALineWithNoIdentifierAtAll() {
        PreparedImport prepared = service().prepare(document(List.of(entry(null, null, "999908", 1))), MANIFEST_ID);

        assertThat(prepared.quarantined())
                .singleElement()
                .satisfies(line -> assertThat(line.reason()).isEqualTo(UnmatchedLineReason.NO_IDENTIFIER));
    }

    @Test
    void quarantinesAMissAsCatalogWork() {
        when(productCodeResolver.resolve("EAN", "3528709999083")).thenReturn(new Resolution.NotFound());
        when(productCodeResolver.resolve("UPC", "0123456789012")).thenReturn(new Resolution.NotFound());

        PreparedImport prepared =
                service().prepare(document(List.of(entry("3528709999083", "0123456789012", "999908", 1))), MANIFEST_ID);

        assertThat(prepared.quarantined())
                .singleElement()
                .satisfies(line -> assertThat(line.reason()).isEqualTo(UnmatchedLineReason.NO_CATALOG_MATCH));
    }

    @Test
    void refusesToGuessWhenTheCatalogReportsAmbiguity() {
        when(productCodeResolver.resolve("EAN", "3528709999083")).thenReturn(new Resolution.Ambiguous("two products"));

        PreparedImport prepared =
                service().prepare(document(List.of(entry("3528709999083", "0123456789012", "999908", 1))), MANIFEST_ID);

        assertThat(prepared.matched()).isEmpty();
        assertThat(prepared.quarantined())
                .singleElement()
                .satisfies(line -> assertThat(line.reason()).isEqualTo(UnmatchedLineReason.AMBIGUOUS_CATALOG_MATCH));
        // Ambiguity stops the search: trying the cross-reference next could attach the line to a
        // different product than the EAN pointed at.
        verify(productCodeResolver, never()).resolve(eq("UPC"), any());
    }

    @Test
    void marksAnUnreachableCatalogAsRetryableRatherThanAsAMiss() {
        when(productCodeResolver.resolve("EAN", "3528709999083"))
                .thenReturn(new Resolution.Unavailable("connect timeout"));
        when(productCodeResolver.resolve("UPC", "0123456789012")).thenReturn(new Resolution.NotFound());

        PreparedImport prepared =
                service().prepare(document(List.of(entry("3528709999083", "0123456789012", "999908", 1))), MANIFEST_ID);

        assertThat(prepared.quarantined())
                .singleElement()
                .satisfies(line -> assertThat(line.reason()).isEqualTo(UnmatchedLineReason.CATALOG_UNAVAILABLE));
    }

    @Test
    void dedupesRepeatedIdentifiersWithinOneDocumentAndKeepsTheFirst() {
        when(productCodeResolver.resolve("EAN", "3528709999083")).thenReturn(new Resolution.Matched(PRODUCT_ID));

        PreparedImport prepared = service()
                .prepare(
                        document(List.of(
                                entry("3528709999083", null, "999908", 1), entry("3528709999083", null, "999908", 2))),
                        MANIFEST_ID);

        assertThat(prepared.matched()).hasSize(1);
        assertThat(prepared.matched().getFirst().entry().positionNumber()).isEqualTo(1);
        assertThat(prepared.duplicates()).isEqualTo(1);
        assertThat(prepared.quarantined())
                .singleElement()
                .satisfies(line -> assertThat(line.reason()).isEqualTo(UnmatchedLineReason.DUPLICATE_LINE));
    }

    @Test
    void quarantinesLinesTheCodecCouldNotDecode() {
        PricatDocument withRejects = new PricatDocument(
                "DOC-1",
                LocalDate.of(2026, 1, 1),
                "SE",
                "SEK",
                List.of(),
                List.of(new PricatDocument.RejectedLine(
                        7, "3528709999999", "777701", null, "grossPrice is not a number")),
                1);

        PreparedImport prepared = service().prepare(withRejects, MANIFEST_ID);

        assertThat(prepared.quarantined()).singleElement().satisfies(line -> {
            assertThat(line.reason()).isEqualTo(UnmatchedLineReason.MALFORMED_LINE);
            assertThat(line.positionNumber()).isEqualTo(7);
            assertThat(line.detail()).contains("not a number");
        });
    }

    @Test
    void accountsForEveryLineTheVendorSent() {
        when(productCodeResolver.resolve("EAN", "3528709999083")).thenReturn(new Resolution.Matched(PRODUCT_ID));
        when(productCodeResolver.resolve("EAN", "3528709999084")).thenReturn(new Resolution.NotFound());

        PricatDocument document = new PricatDocument(
                "DOC-1",
                LocalDate.of(2026, 1, 1),
                "SE",
                "SEK",
                List.of(
                        entry("3528709999083", null, "999908", 1),
                        entry("3528709999084", null, "888801", 2),
                        entry("3528709999083", null, "999908", 3)),
                List.of(new PricatDocument.RejectedLine(4, null, null, null, "unusable")),
                4);

        PreparedImport prepared = service().prepare(document, MANIFEST_ID);

        int unmatched = prepared.quarantined().size() - prepared.duplicates();
        assertThat(prepared.matched().size() + unmatched + prepared.duplicates())
                .isEqualTo(4);
    }
}
