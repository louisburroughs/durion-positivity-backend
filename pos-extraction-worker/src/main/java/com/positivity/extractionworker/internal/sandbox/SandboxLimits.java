package com.positivity.extractionworker.internal.sandbox;

/**
 * The limits the child JVM enforces, handed to it in {@code request.json}. Built from
 * {@code pos.extraction-worker.*} by the parent; the child has no configuration of its own.
 *
 * @param maxPdfPages most pages a PDF may have
 * @param maxTiffFrames most frames a TIFF may have
 * @param maxImagePixels most pixels one image may declare in its header
 * @param maxRenderPixels most pixels in a rendered page; DPI is lowered to fit
 * @param renderDpi resolution a PDF page is rendered at
 * @param maxRows most rows a spreadsheet may have
 * @param maxColumns most columns a spreadsheet row may have
 * @param maxCellChars most characters one cell may hold
 * @param maxZipEntries most entries an XLSX archive may hold
 * @param maxZipEntryBytes most bytes one XLSX entry may inflate to
 * @param maxZipTotalBytes most bytes the whole XLSX archive may inflate to
 * @param minInflateRatio smallest compressed to inflated ratio an entry may show
 * @param heifBinary the HEIC decoder command (resolved on PATH)
 * @param heifTimeoutSeconds deadline for the HEIC decoder
 * @param heifMaxMemoryMb address-space limit (ulimit -v) for the HEIC decoder
 * @param maxStreamCacheBytes most bytes PDFBox may spill to its temp-file stream cache
 */
public record SandboxLimits(
        int maxPdfPages,
        int maxTiffFrames,
        long maxImagePixels,
        long maxRenderPixels,
        int renderDpi,
        int maxRows,
        int maxColumns,
        int maxCellChars,
        int maxZipEntries,
        long maxZipEntryBytes,
        long maxZipTotalBytes,
        double minInflateRatio,
        String heifBinary,
        int heifTimeoutSeconds,
        long heifMaxMemoryMb,
        long maxStreamCacheBytes) {}
