package com.positivity.accounting.internal.billintake.service;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The seam to the extraction worker (CAP:550 S25 ruling 1, S26 #2519): the one place untrusted files are opened. Four
 * operations, whose result shapes are the worker's wire contract ({@code /extraction-worker/v1}), so the worker-backed
 * adapter of S26 changes no caller. pos-accounting parses no file itself.
 *
 * <p>Every operation answers {@code UNAVAILABLE} when the worker cannot be reached: the file stays quarantined and the
 * step is tried again (ruling 2); there is no "check unavailable" rejection. Only {@link UnavailableExtractionPort}
 * ships in S25 Part B. Implementations never log bytes, names, extracted values or provider bodies (ADR-0072).
 */
public interface ExtractionPort {

    /** The type a file is declared (and detected) to be. */
    enum DeclaredType {
        PDF,
        JPEG,
        PNG,
        HEIC,
        TIFF,
        CSV,
        XLSX
    }

    /** What a document is, for the provider's read. */
    enum DocumentKind {
        BILL,
        VENDOR_STATEMENT
    }

    /** Opens the file in the worker's sandbox and refuses what is unsafe or unreadable. No pages, no CSV copy. */
    @NonNull
    InspectionResult inspect(byte @NonNull [] file, @NonNull DeclaredType declaredType);

    /** One page of a PDF or image as a PNG with metadata stripped. */
    @NonNull
    RenderResult render(byte @NonNull [] file, @NonNull DeclaredType declaredType, int page);

    /** The raw text cells of a CSV or XLSX; grouping rows into invoices is pos-accounting's. */
    @NonNull
    TabulationResult tabulate(byte @NonNull [] file, @NonNull DeclaredType declaredType);

    /** Reads a document with the extraction provider the worker is configured with. */
    @NonNull
    ExtractionResult extract(
            byte @NonNull [] file,
            @NonNull DeclaredType declaredType,
            @NonNull DocumentKind documentKind,
            @NonNull String localeHint);

    /** {@code ACCEPTED}, {@code REJECTED} with a reason code, or the worker could not be asked. */
    enum InspectionVerdict {
        ACCEPTED,
        REJECTED,
        UNAVAILABLE
    }

    /**
     * @param pageCount PDF pages, TIFF frames, 1 for a single image, the data-row count for CSV and XLSX
     */
    record InspectionResult(
            @NonNull InspectionVerdict verdict,
            @Nullable String reasonCode,
            @Nullable DeclaredType detectedType,
            int pageCount,
            @Nullable String sha256) {

        public static @NonNull InspectionResult unavailable() {
            return new InspectionResult(InspectionVerdict.UNAVAILABLE, null, null, 0, null);
        }
    }

    enum RenderOutcome {
        RENDERED,
        NOT_RENDERABLE,
        UNAVAILABLE
    }

    /** @param png the page as PNG bytes when {@code RENDERED} */
    record RenderResult(@NonNull RenderOutcome outcome, byte @Nullable [] png, @Nullable String reasonCode) {

        public static @NonNull RenderResult unavailable() {
            return new RenderResult(RenderOutcome.UNAVAILABLE, null, null);
        }
    }

    enum TabulationOutcome {
        TABULATED,
        FAILED,
        UNAVAILABLE
    }

    /** A cell by its 1-based row and column. */
    record CellRef(int row, int column) {}

    /**
     * @param rows raw text cells, row 1 the first physical row; null for an empty cell
     * @param formulaCells cells that held a formula
     * @param dateCells cells that held a date
     */
    record TabulationResult(
            @NonNull TabulationOutcome outcome,
            @Nullable String reasonCode,
            @Nullable String sheetName,
            @NonNull List<List<String>> rows,
            @NonNull List<CellRef> formulaCells,
            @NonNull List<CellRef> dateCells) {

        public static @NonNull TabulationResult unavailable() {
            return new TabulationResult(TabulationOutcome.UNAVAILABLE, null, null, List.of(), List.of(), List.of());
        }
    }

    enum ExtractionOutcome {
        EXTRACTED,
        UNAVAILABLE,
        FAILED
    }

    /** A box on a page, in fractions of the page. */
    record Box(double left, double top, double width, double height) {}

    /** One read field: the text as read, a normalised value when the provider gave one, its confidence and place. */
    record ExtractedField(
            @NonNull String value,
            @Nullable String normalizedValue,
            double confidence,
            @Nullable Integer page,
            @Nullable Box box) {}

    /** One document found in the file: its pages, header fields and line items. */
    record ExtractedDocument(
            int pageFrom,
            int pageTo,
            @NonNull Map<String, ExtractedField> fields,
            @NonNull List<Map<String, ExtractedField>> lines) {}

    record ExtractionResult(
            @NonNull ExtractionOutcome outcome,
            @Nullable String reasonCode,
            @Nullable String provider,
            int pagesSentToProvider,
            @NonNull List<ExtractedDocument> documents) {

        public static @NonNull ExtractionResult unavailable() {
            return new ExtractionResult(ExtractionOutcome.UNAVAILABLE, "PROVIDER_NOT_CONFIGURED", null, 0, List.of());
        }
    }
}
