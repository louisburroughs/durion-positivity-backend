package com.positivity.extractionworker.internal.sandbox;

import com.positivity.extractionworker.internal.enums.ReasonCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

/**
 * PDF checks, in the order the contract fixes: encryption, page count, active content. Runs in the child
 * JVM only. The document is loaded with a temp-file-only stream cache (the temp directory is the call
 * directory) so a stream bomb costs scratch space, not heap, and the page tree is walked by counting
 * real leaves rather than trusting {@code /Count}.
 */
final class PdfInspector {

    private static final COSName JS = COSName.getPDFName("JS");
    private static final COSName JAVASCRIPT = COSName.getPDFName("JavaScript");
    private static final COSName EMBEDDED_FILES = COSName.getPDFName("EmbeddedFiles");
    private static final COSName EMBEDDED_FILE = COSName.getPDFName("EmbeddedFile");
    private static final COSName EF = COSName.getPDFName("EF");
    private static final COSName FILE_ATTACHMENT = COSName.getPDFName("FileAttachment");
    private static final COSName XFA = COSName.getPDFName("XFA");

    /**
     * Action types ({@code /S}) that run code, leave the file or reach out. An action dictionary is judged by
     * its type wherever it sits (catalog, page, annotation, field, a {@code /Next} chain), so a plain
     * go-to-page {@code /OpenAction} or {@code /AA} on a generated invoice is not active content.
     */
    private static final Set<String> RISKY_ACTIONS =
            Set.of("JavaScript", "Launch", "SubmitForm", "ImportData", "GoToR", "URI");

    private PdfInspector() {}

    static SandboxResult inspect(Path input, SandboxLimits limits) {
        try (PDDocument document = load(input, limits.maxStreamCacheBytes())) {
            if (document.isEncrypted()) {
                return SandboxResult.rejected(ReasonCode.ENCRYPTED_PDF);
            }
            int pages = countPages(document, limits.maxPdfPages());
            if (pages > limits.maxPdfPages()) {
                return SandboxResult.rejected(ReasonCode.PAGE_LIMIT_EXCEEDED);
            }
            if (pages == 0) {
                return SandboxResult.rejected(ReasonCode.UNREADABLE);
            }
            if (hasActiveContent(document.getDocument())) {
                return SandboxResult.rejected(ReasonCode.PDF_ACTIVE_CONTENT);
            }
            return SandboxResult.accepted(pages);
        } catch (InvalidPasswordException e) {
            return SandboxResult.rejected(ReasonCode.ENCRYPTED_PDF);
        } catch (IOException e) {
            // PDFBox's fixed message when the temp-file stream cache passes its cap.
            boolean cacheBreach = e.getMessage() != null && e.getMessage().contains("scratch file memory exceeded");
            return SandboxResult.rejected(cacheBreach ? ReasonCode.RESOURCE_LIMIT_EXCEEDED : ReasonCode.UNREADABLE);
        } catch (RuntimeException e) {
            return SandboxResult.rejected(ReasonCode.UNREADABLE);
        }
    }

    /**
     * Opens the file with the empty password and a temp-file-only stream cache capped at
     * {@code maxCacheBytes}, so a stream bomb costs bounded scratch space and never heap.
     */
    static PDDocument load(Path input, long maxCacheBytes) throws IOException {
        return Loader.loadPDF(
                new RandomAccessReadBufferedFile(input.toFile()),
                "",
                null,
                null,
                MemoryUsageSetting.setupTempFileOnly(maxCacheBytes).streamCache);
    }

    /** Counts page-tree leaves, stopping one past {@code limit}. */
    static int countPages(PDDocument document, int limit) {
        int count = 0;
        for (PDPage ignored : document.getPages()) {
            count++;
            if (count > limit) {
                break;
            }
        }
        return count;
    }

    /**
     * Walks every object in the file, not only the catalog, looking for JavaScript, launch actions,
     * embedded files, file attachments and automatic actions. Iterative, so a deeply nested file cannot
     * overflow the stack; each object is visited once, so a cyclic file terminates.
     */
    private static boolean hasActiveContent(COSDocument cosDocument) throws IOException {
        Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<COSBase> pending = new ArrayDeque<>();
        pending.push(cosDocument.getTrailer());
        for (Map.Entry<COSObjectKey, Long> entry : cosDocument.getXrefTable().entrySet()) {
            COSObject object = cosDocument.getObjectFromPool(entry.getKey());
            pending.push(object);
        }
        while (!pending.isEmpty()) {
            COSBase node = pending.pop();
            if (node instanceof COSObject indirect) {
                node = indirect.getObject();
            }
            if (node == null || !seen.add(node)) {
                continue;
            }
            if (node instanceof COSDictionary dictionary) {
                if (isActive(dictionary)) {
                    return true;
                }
                for (Map.Entry<COSName, COSBase> item : dictionary.entrySet()) {
                    pending.push(item.getValue());
                }
            } else if (node instanceof COSArray array) {
                for (int i = 0; i < array.size(); i++) {
                    pending.push(array.get(i));
                }
            }
        }
        return false;
    }

    private static boolean isActive(COSDictionary dictionary) {
        if (dictionary.containsKey(JS)
                || dictionary.containsKey(JAVASCRIPT)
                || dictionary.containsKey(EMBEDDED_FILES)
                || dictionary.containsKey(EF)
                || dictionary.containsKey(XFA)) {
            return true;
        }
        COSName action = dictionary.getCOSName(COSName.S);
        if (action != null && RISKY_ACTIONS.contains(action.getName())) {
            return true;
        }
        return EMBEDDED_FILE.equals(dictionary.getCOSName(COSName.TYPE))
                || FILE_ATTACHMENT.equals(dictionary.getCOSName(COSName.SUBTYPE));
    }
}
