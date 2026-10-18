package com.positivity.extractionworker.internal.sandbox;

import com.positivity.extractionworker.internal.enums.ReasonCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * XLSX checks, in the order the contract fixes: zip-bomb limits, macro content, XML entity declarations,
 * then the row, column and cell limits on the first sheet. The archive is walked with
 * {@link java.util.zip.ZipFile} before POI opens it, counting the bytes actually inflated rather than
 * trusting any declared size. The sheet is then streamed with SAX, never loaded as a workbook, and no
 * formula is evaluated. {@code pageCount} is the number of data rows: the non-empty rows after the first.
 */
final class OoxmlInspector {

    private static final int CHUNK = 64 * 1024;
    private static final long RATIO_GRACE_BYTES = 1024 * 1024;
    private static final byte[] DOCTYPE = "<!DOCTYPE".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ENTITY = "<!ENTITY".getBytes(StandardCharsets.US_ASCII);

    private OoxmlInspector() {}

    static SandboxResult inspect(Path input, SandboxLimits limits) throws IOException {
        ReasonCode archiveProblem = scanArchive(input, limits);
        if (archiveProblem != null) {
            return SandboxResult.rejected(archiveProblem);
        }
        ZipSecureFile.setMinInflateRatio(limits.minInflateRatio());
        ZipSecureFile.setMaxEntrySize(limits.maxZipEntryBytes());
        ZipSecureFile.setMaxFileCount(limits.maxZipEntries());
        try (OPCPackage pkg = OPCPackage.open(input.toFile(), PackageAccess.READ)) {
            return countRows(pkg, limits);
        } catch (InvalidFormatException | OpenXML4JException | SAXException | ParserConfigurationException e) {
            return SandboxResult.rejected(ReasonCode.UNREADABLE);
        }
    }

    // ---- archive walk ------------------------------------------------------------------------------

    private static ReasonCode scanArchive(Path input, SandboxLimits limits) throws IOException {
        boolean macro = false;
        boolean entity = false;
        long total = 0;
        try (ZipFile zip = new ZipFile(input.toFile())) {
            if (zip.size() > limits.maxZipEntries()) {
                return ReasonCode.DECOMPRESSION_LIMIT_EXCEEDED;
            }
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase(Locale.ROOT);
                macro |= name.contains("vbaproject");
                boolean xml = name.endsWith(".xml") || name.endsWith(".rels") || name.endsWith(".vml");
                boolean contentTypes = name.equals("[content_types].xml");
                EntryScan scan = readEntry(zip, entry, xml, contentTypes, limits, limits.maxZipTotalBytes() - total);
                if (scan.breach()) {
                    return ReasonCode.DECOMPRESSION_LIMIT_EXCEEDED;
                }
                total += scan.inflated();
                macro |= scan.macroContentType();
                entity |= scan.entityDeclared();
            }
        } catch (ZipException e) {
            return ReasonCode.UNREADABLE;
        }
        if (macro) {
            return ReasonCode.MACRO_CONTENT;
        }
        return entity ? ReasonCode.EXTERNAL_ENTITY : null;
    }

    private record EntryScan(long inflated, boolean breach, boolean entityDeclared, boolean macroContentType) {}

    private static EntryScan readEntry(
            ZipFile zip, ZipEntry entry, boolean xml, boolean contentTypes, SandboxLimits limits, long totalRemaining)
            throws IOException {
        long inflated = 0;
        boolean entity = false;
        boolean macroType = false;
        StringBuilder types = contentTypes ? new StringBuilder() : null;
        byte[] carry = new byte[0];
        byte[] chunk = new byte[CHUNK];
        long compressed = entry.getCompressedSize();
        boolean first = true;
        try (InputStream in = zip.getInputStream(entry)) {
            int read;
            while ((read = in.read(chunk)) > 0) {
                inflated += read;
                if (inflated > limits.maxZipEntryBytes() || inflated > totalRemaining) {
                    return new EntryScan(inflated, true, false, false);
                }
                if (compressed > 0
                        && inflated > RATIO_GRACE_BYTES
                        && (double) compressed / inflated < limits.minInflateRatio()) {
                    return new EntryScan(inflated, true, false, false);
                }
                if (xml) {
                    if (first && hidesEncoding(chunk, read)) {
                        entity = true;
                    }
                    byte[] window = concat(carry, chunk, read);
                    entity |= contains(window, DOCTYPE) || contains(window, ENTITY);
                    carry = tail(window, DOCTYPE.length - 1);
                }
                if (types != null && types.length() < CHUNK) {
                    types.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
                }
                first = false;
            }
        }
        if (types != null) {
            String lower = types.toString().toLowerCase(Locale.ROOT);
            macroType = lower.contains("macroenabled") || lower.contains("vbaproject");
        }
        return new EntryScan(inflated, false, entity, macroType);
    }

    /** A byte-order mark or NUL-padded start: UTF-16 or UTF-32 XML would hide a declaration from the byte scan. */
    private static boolean hidesEncoding(byte[] chunk, int read) {
        if (read < 2) {
            return false;
        }
        int a = chunk[0] & 0xFF;
        int b = chunk[1] & 0xFF;
        return (a == 0xFE && b == 0xFF) || (a == 0xFF && b == 0xFE) || (a == 0x00 && b == 0x3C) || (a == 0x3C && b == 0x00);
    }

    private static byte[] concat(byte[] carry, byte[] chunk, int read) {
        byte[] window = new byte[carry.length + read];
        System.arraycopy(carry, 0, window, 0, carry.length);
        System.arraycopy(chunk, 0, window, carry.length, read);
        return window;
    }

    private static byte[] tail(byte[] window, int length) {
        int keep = Math.min(length, window.length);
        byte[] out = new byte[keep];
        System.arraycopy(window, window.length - keep, out, 0, keep);
        return out;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    // ---- first sheet -------------------------------------------------------------------------------

    private static SandboxResult countRows(OPCPackage pkg, SandboxLimits limits)
            throws IOException, OpenXML4JException, SAXException, ParserConfigurationException {
        XSSFReader reader = new XSSFReader(pkg);
        ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
        XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
        if (!sheets.hasNext()) {
            return SandboxResult.rejected(ReasonCode.UNREADABLE);
        }
        SheetCounter counter = new SheetCounter(strings, limits);
        try (InputStream sheet = sheets.next()) {
            XMLReader xml = XMLHelper.newXMLReader();
            xml.setContentHandler(counter);
            xml.parse(new InputSource(sheet));
        } catch (LimitBreach e) {
            return SandboxResult.rejected(ReasonCode.RESOURCE_LIMIT_EXCEEDED);
        }
        if (counter.nonEmptyRows == 0) {
            return SandboxResult.rejected(ReasonCode.UNREADABLE);
        }
        return SandboxResult.accepted(counter.nonEmptyRows - 1);
    }

    /** A sheet limit was passed. Stackless: a hostile file can raise it once per parse only. */
    private static final class LimitBreach extends SAXException {
        private static final long serialVersionUID = 1L;

        LimitBreach() {
            super("limit", null);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private static final class SheetCounter extends DefaultHandler {
        private final ReadOnlySharedStringsTable strings;
        private final SandboxLimits limits;
        int nonEmptyRows;
        private int rows;
        private int column;
        private boolean rowHasData;
        private boolean inText;
        private boolean sharedString;
        private final StringBuilder text = new StringBuilder();

        SheetCounter(ReadOnlySharedStringsTable strings, SandboxLimits limits) {
            this.strings = strings;
            this.limits = limits;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes)
                throws SAXException {
            switch (localName) {
                case "row" -> {
                    rows++;
                    if (rows > limits.maxRows()) {
                        throw new LimitBreach();
                    }
                    rowHasData = false;
                    column = 0;
                }
                case "c" -> {
                    String ref = attributes.getValue("r");
                    column = ref == null ? column + 1 : CellReference.convertColStringToIndex(letters(ref)) + 1;
                    if (column > limits.maxColumns()) {
                        throw new LimitBreach();
                    }
                    sharedString = "s".equals(attributes.getValue("t"));
                }
                case "v", "t" -> {
                    inText = true;
                    text.setLength(0);
                }
                default -> {
                    // other elements carry no cell text
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            if (!inText) {
                return;
            }
            if (text.length() + length > limits.maxCellChars()) {
                throw new LimitBreach();
            }
            text.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            switch (localName) {
                case "v", "t" -> {
                    String value = text.toString();
                    if (sharedString && "v".equals(localName) && !value.isBlank()) {
                        value = strings.getItemAt(Integer.parseInt(value.trim())).getString();
                        if (value.length() > limits.maxCellChars()) {
                            throw new LimitBreach();
                        }
                    }
                    rowHasData |= !value.isBlank();
                    inText = false;
                    text.setLength(0);
                }
                case "row" -> {
                    if (rowHasData) {
                        nonEmptyRows++;
                    }
                }
                default -> {
                    // nothing to close
                }
            }
        }

        private static String letters(String ref) {
            int i = 0;
            while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
                i++;
            }
            return ref.substring(0, i);
        }
    }
}
