package com.positivity.extractionworker;

import com.positivity.extractionworker.internal.sandbox.SandboxLimits;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Builds the test files in code: valid ones and the malicious corpus. Nothing here is committed as bytes. */
public final class TestCorpus {

    private TestCorpus() {}

    /** The production defaults. */
    public static SandboxLimits limits() {
        return new SandboxLimits(
                30,
                30,
                50_000_000L,
                25_000_000L,
                150,
                10_000,
                100,
                1_000,
                10_000,
                104_857_600L,
                209_715_200L,
                0.01,
                "heif-dec",
                20);
    }

    // ---- PDF ---------------------------------------------------------------------------------------

    public static byte[] pdf(int pages) {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 18);
                    content.newLineAtOffset(72, 700);
                    content.showText("Test bill page " + (i + 1));
                    content.endText();
                }
            }
            return save(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] encryptedPdf(String userPassword) {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy("owner-secret", userPassword, new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            return save(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] pdfWithJavaScript() {
        return pdfWithCatalogItem("OpenAction", action("JavaScript", "JS", "app.alert('x')"));
    }

    public static byte[] pdfWithLaunchAction() {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDAnnotationLink link = new PDAnnotationLink();
            link.getCOSObject().setItem(COSName.A, action("Launch", "F", "calc.exe"));
            page.setAnnotations(java.util.List.of(link));
            return save(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] pdfWithEmbeddedFiles() {
        COSDictionary names = new COSDictionary();
        names.setItem(COSName.getPDFName("EmbeddedFiles"), new COSDictionary());
        return pdfWithCatalogItem("Names", names);
    }

    /** A JavaScript action hidden in an annotation's additional-actions dictionary, not on the catalog. */
    public static byte[] pdfWithAnnotationJavaScript() {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDAnnotationLink link = new PDAnnotationLink();
            COSDictionary additional = new COSDictionary();
            additional.setItem(COSName.getPDFName("E"), action("JavaScript", "JS", "app.alert('y')"));
            link.getCOSObject().setItem(COSName.getPDFName("AA"), additional);
            page.setAnnotations(java.util.List.of(link));
            return save(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] pdfWithCatalogItem(String key, org.apache.pdfbox.cos.COSBase value) {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName(key), value);
            return save(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static COSDictionary action(String type, String payloadKey, String payload) {
        COSDictionary action = new COSDictionary();
        action.setItem(COSName.S, COSName.getPDFName(type));
        action.setString(COSName.getPDFName(payloadKey), payload);
        return action;
    }

    /** A PDF whose catalog carries an array nested {@code depth} levels deep, written as raw bytes. */
    public static byte[] deeplyNestedPdf(int depth) {
        StringBuilder nested = new StringBuilder();
        nested.append("[".repeat(depth)).append("]".repeat(depth));
        String body = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R/X " + nested + ">>endobj\n"
                + "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
                + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n"
                + "trailer<</Root 1 0 R/Size 4>>\n%%EOF\n";
        return body.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }

    // ---- images ------------------------------------------------------------------------------------

    public static byte[] image(String format, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, (x * 7 + y * 13) & 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("no writer for " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** A multi-frame TIFF of tiny frames, written with the JDK's TIFF writer sequence. */
    public static byte[] tiff(int frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            writer.prepareWriteSequence(null);
            for (int i = 0; i < frames; i++) {
                BufferedImage frame = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
                frame.setRGB(i % 16, i % 16, 0xFF0000);
                writer.writeToSequence(new IIOImage(frame, null, null), param);
            }
            writer.endWriteSequence();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /**
     * A PNG whose header declares {@code width} x {@code height} but which holds no pixel data worth
     * decoding: a decoder that trusts the header would try to allocate it.
     */
    public static byte[] pngDeclaring(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(width)
                .putInt(height)
                .put((byte) 8)
                .put((byte) 2)
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 0);
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IDAT", new byte[] {0x78, (byte) 0x9C, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01});
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    // ---- spreadsheets ------------------------------------------------------------------------------

    public static byte[] csv(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** An XLSX whose first sheet holds a header row and {@code dataRows} rows of text and numbers. */
    public static byte[] xlsx(int dataRows, int columns) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Bills");
            for (int r = 0; r <= dataRows; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < columns; c++) {
                    if (r == 0) {
                        row.createCell(c).setCellValue("H" + c);
                    } else if (c % 2 == 0) {
                        row.createCell(c).setCellValue("v" + r + "-" + c);
                    } else {
                        row.createCell(c).setCellValue(r * 1.5 + c);
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] xlsxWithLongCell(int chars) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Bills");
            sheet.createRow(0).createCell(0).setCellValue("header");
            sheet.createRow(1).createCell(0).setCellValue("x".repeat(chars));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] xlsxWithVbaProject() {
        return rewriteZip(xlsx(2, 2), Map.of(), Map.of("xl/vbaProject.bin", new byte[] {1, 2, 3}));
    }

    /** A shared-strings part that opens with a DOCTYPE declaring an external entity (XXE). */
    public static byte[] xlsxWithEntity() {
        String doctype = "<!DOCTYPE sst [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>";
        return rewriteZip(
                xlsx(2, 2),
                Map.of("xl/sharedStrings.xml", original -> prefixAfterXmlDeclaration(original, doctype)),
                Map.of());
    }

    /** A valid workbook plus one 24 MiB entry of zeros: tiny on disk, a bomb when inflated. */
    public static byte[] xlsxZipBomb() {
        byte[] bomb = new byte[24 * 1024 * 1024];
        return rewriteZip(xlsx(2, 2), Map.of(), Map.of("xl/bomb.bin", bomb));
    }

    private static byte[] prefixAfterXmlDeclaration(byte[] xml, String insertion) {
        String text = new String(xml, StandardCharsets.UTF_8);
        int end = text.indexOf("?>");
        String result = text.substring(0, end + 2) + insertion + text.substring(end + 2);
        return result.getBytes(StandardCharsets.UTF_8);
    }

    /** Rewrites an archive, replacing entries through {@code replacements} and appending {@code extras}. */
    public static byte[] rewriteZip(
            byte[] zip,
            Map<String, java.util.function.UnaryOperator<byte[]>> replacements,
            Map<String, byte[]> extras) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip));
                ZipOutputStream target = new ZipOutputStream(out)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                byte[] content = in.readAllBytes();
                var replacement = replacements.get(entry.getName());
                target.putNextEntry(new ZipEntry(entry.getName()));
                target.write(replacement == null ? content : replacement.apply(content));
                target.closeEntry();
            }
            Map<String, byte[]> add = new LinkedHashMap<>(extras);
            for (Map.Entry<String, byte[]> extra : add.entrySet()) {
                target.putNextEntry(new ZipEntry(extra.getKey()));
                target.write(extra.getValue());
                target.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
