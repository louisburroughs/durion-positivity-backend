package com.positivity.extractionworker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.extractionworker.internal.security.TenantLabelFilter;
import com.positivity.extractionworker.internal.security.WorkerSecretFilter;
import com.positivity.extractionworker.internal.service.ParsePool;
import com.positivity.extractionworker.internal.service.ScratchSpace;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/** The worker API end to end through MockMvc, with a real child JVM per parse. */
@SpringBootTest
@AutoConfigureMockMvc
class WorkerApiTest {

    static final String SECRET = "test-worker-secret";
    static final String TENANT = "01900000-0000-7000-8000-000000000001";

    @Autowired
    MockMvc mvc;

    @Autowired
    ScratchSpace scratch;

    @Autowired
    ParsePool pool;

    @Autowired
    MeterRegistry meters;

    @AfterEach
    void scratchIsEmptyAfterEveryRequest() throws IOException {
        try (Stream<Path> entries = Files.list(scratch.root())) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    static MockMultipartHttpServletRequestBuilder post(String path, String filename, byte[] bytes) {
        return multipart(WorkerSecretFilter.PATH_PREFIX + path)
                .file(new MockMultipartFile("file", filename, "application/octet-stream", bytes));
    }

    static MockMultipartHttpServletRequestBuilder authorised(MockMultipartHttpServletRequestBuilder builder) {
        return (MockMultipartHttpServletRequestBuilder)
                builder.header(WorkerSecretFilter.SECRET_HEADER, SECRET).header(TenantLabelFilter.TENANT_HEADER, TENANT);
    }

    private ResultActions inspect(String declaredType, byte[] bytes) throws Exception {
        return mvc.perform(authorised(post("/inspections", "bill.bin", bytes)).param("declaredType", declaredType));
    }

    private double calls() {
        return meters.find("extraction.calls").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    // ---- guards: nothing is read or parsed before both pass ---------------------------------------

    @Test
    void missingOrWrongSecretIs401AndNothingIsParsed() throws Exception {
        byte[] pdf = TestCorpus.pdf(1);
        double before = calls();

        mvc.perform(post("/inspections", "bill.pdf", pdf)
                        .header(TenantLabelFilter.TENANT_HEADER, TENANT)
                        .param("declaredType", "PDF"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_EXTRACTION_WORKER_SECRET"));
        mvc.perform(post("/inspections", "bill.pdf", pdf)
                        .header(WorkerSecretFilter.SECRET_HEADER, "not-the-secret")
                        .header(TenantLabelFilter.TENANT_HEADER, TENANT)
                        .param("declaredType", "PDF"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_EXTRACTION_WORKER_SECRET"));

        assertThat(calls()).isEqualTo(before);
    }

    @Test
    void tenantHeaderMustBeAUuidAndIsCheckedAfterTheSecret() throws Exception {
        byte[] pdf = TestCorpus.pdf(1);
        double before = calls();

        mvc.perform(post("/inspections", "bill.pdf", pdf)
                        .header(WorkerSecretFilter.SECRET_HEADER, SECRET)
                        .param("declaredType", "PDF"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        mvc.perform(post("/inspections", "bill.pdf", pdf)
                        .header(WorkerSecretFilter.SECRET_HEADER, SECRET)
                        .header(TenantLabelFilter.TENANT_HEADER, "not-a-uuid-at-all-not-a-uuid-at-all!")
                        .param("declaredType", "PDF"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        // A wrong secret wins over a missing tenant: an unauthenticated caller learns nothing else.
        mvc.perform(post("/inspections", "bill.pdf", pdf).param("declaredType", "PDF"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_EXTRACTION_WORKER_SECRET"));
        assertThat(calls()).isEqualTo(before);
    }

    @Test
    void missingPartsAndUnknownTypesAre400() throws Exception {
        mvc.perform(authorised(multipart(WorkerSecretFilter.PATH_PREFIX + "/inspections")).param("declaredType", "PDF"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(authorised(post("/inspections", "bill.pdf", TestCorpus.pdf(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(authorised(post("/inspections", "bill.pdf", TestCorpus.pdf(1))).param("declaredType", "DOCX"))
                .andExpect(status().isBadRequest());
    }

    // ---- inspect -----------------------------------------------------------------------------------

    @Test
    void validThreePagePdfIsAcceptedWithItsHash() throws Exception {
        byte[] pdf = TestCorpus.pdf(3);

        inspect("PDF", pdf)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("ACCEPTED"))
                .andExpect(jsonPath("$.pageCount").value(3))
                .andExpect(jsonPath("$.detectedType").value("PDF"))
                .andExpect(jsonPath("$.sha256").value(sha256(pdf)))
                .andExpect(jsonPath("$.reasonCode").doesNotExist());
    }

    @Test
    void maliciousPdfsAreRejectedWithTheirReasons() throws Exception {
        inspect("PDF", TestCorpus.pdf(31))
                .andExpect(jsonPath("$.verdict").value("REJECTED"))
                .andExpect(jsonPath("$.reasonCode").value("PAGE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.pageCount").value(0));
        inspect("PDF", TestCorpus.encryptedPdf("pw"))
                .andExpect(jsonPath("$.reasonCode").value("ENCRYPTED_PDF"));
        inspect("PDF", TestCorpus.pdfWithJavaScript())
                .andExpect(jsonPath("$.reasonCode").value("PDF_ACTIVE_CONTENT"));
    }

    @Test
    void declaredTypeThatTheBytesContradictIsATypeMismatchWithoutAChild() throws Exception {
        inspect("PDF", TestCorpus.image("png", 10, 10))
                .andExpect(jsonPath("$.verdict").value("REJECTED"))
                .andExpect(jsonPath("$.reasonCode").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.detectedType").value("PNG"));
        inspect("PNG", "just some text".getBytes())
                .andExpect(jsonPath("$.reasonCode").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.detectedType").value("UNKNOWN"));
    }

    @Test
    void emptyFileIsUnreadable() throws Exception {
        inspect("PDF", new byte[0]).andExpect(jsonPath("$.reasonCode").value("UNREADABLE"));
    }

    @Test
    void imagesTiffsAndSpreadsheetsInspectThroughTheChild() throws Exception {
        inspect("PNG", TestCorpus.pngDeclaring(100_000, 100_000))
                .andExpect(jsonPath("$.reasonCode").value("RESOURCE_LIMIT_EXCEEDED"));
        inspect("TIFF", TestCorpus.tiff(31)).andExpect(jsonPath("$.reasonCode").value("PAGE_LIMIT_EXCEEDED"));
        inspect("JPEG", TestCorpus.image("jpeg", 30, 30))
                .andExpect(jsonPath("$.verdict").value("ACCEPTED"))
                .andExpect(jsonPath("$.pageCount").value(1));
        inspect("CSV", TestCorpus.csv("a,b\n1,2\n3,4\n"))
                .andExpect(jsonPath("$.verdict").value("ACCEPTED"))
                .andExpect(jsonPath("$.pageCount").value(2))
                .andExpect(jsonPath("$.detectedType").value("CSV"));
        inspect("XLSX", TestCorpus.xlsx(4, 3)).andExpect(jsonPath("$.pageCount").value(4));
        inspect("XLSX", TestCorpus.xlsxWithVbaProject()).andExpect(jsonPath("$.reasonCode").value("MACRO_CONTENT"));
        inspect("XLSX", TestCorpus.xlsxWithEntity()).andExpect(jsonPath("$.reasonCode").value("EXTERNAL_ENTITY"));
        inspect("XLSX", TestCorpus.xlsxZipBomb())
                .andExpect(jsonPath("$.reasonCode").value("DECOMPRESSION_LIMIT_EXCEEDED"));
    }

    @Test
    void fileOverTheSizeLimitIs413() throws Exception {
        byte[] tooBig = new byte[26_214_401];

        inspect("CSV", tooBig)
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void fullPoolIs503WithRetryAfter() throws Exception {
        int taken = 0;
        while (pool.tryAcquire()) {
            taken++;
        }
        try {
            inspect("PDF", TestCorpus.pdf(1))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "5"))
                    .andExpect(jsonPath("$.code").value("WORKER_BUSY"));
        } finally {
            for (int i = 0; i < taken; i++) {
                pool.release();
            }
        }
        assertThat(pool.inUse()).isZero();
    }

    @Test
    void metricsCarryOperationOutcomeAndReason() throws Exception {
        inspect("PDF", TestCorpus.pdf(31)).andExpect(status().isOk());

        assertThat(meters.find("extraction.calls")
                        .tag("operation", "inspect")
                        .tag("outcome", "REJECTED")
                        .tag("reason", "PAGE_LIMIT_EXCEEDED")
                        .tag("tenant", TENANT)
                        .counter())
                .isNotNull();
        assertThat(meters.find("extraction.limit_breaches").counters()).isNotEmpty();
        assertThat(meters.find("extraction.duration").timers()).isNotEmpty();
        assertThat(meters.find("extraction.pool.in_use").gauge()).isNotNull();
    }

    // ---- render ------------------------------------------------------------------------------------

    private ResultActions render(String declaredType, byte[] bytes, String page) throws Exception {
        return mvc.perform(authorised(post("/renderings", "bill.bin", bytes))
                .param("declaredType", declaredType)
                .param("page", page));
    }

    @Test
    void renderReturnsOnePngPerPageAndPageFourIs422() throws Exception {
        byte[] pdf = TestCorpus.pdf(3);

        for (int page = 1; page <= 3; page++) {
            byte[] png = render("PDF", pdf, String.valueOf(page))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "image/png"))
                    .andReturn()
                    .getResponse()
                    .getContentAsByteArray();
            assertThat(png[1]).isEqualTo((byte) 'P');
            assertThat(png).hasSizeGreaterThan(500);
        }
        render("PDF", pdf, "4")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAGE_NOT_RENDERABLE"));
    }

    @Test
    void renderRejectsBadInputs() throws Exception {
        render("PDF", TestCorpus.pdf(1), "0").andExpect(status().isBadRequest());
        render("CSV", TestCorpus.csv("a\n1\n"), "1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        render("PDF", TestCorpus.image("png", 8, 8), "1").andExpect(status().isUnprocessableEntity());
        render("PNG", TestCorpus.image("png", 40, 40), "1").andExpect(status().isOk());
        mvc.perform(post("/renderings", "bill.pdf", TestCorpus.pdf(1))
                        .header(WorkerSecretFilter.SECRET_HEADER, "wrong")
                        .header(TenantLabelFilter.TENANT_HEADER, UUID.randomUUID().toString())
                        .param("declaredType", "PDF")
                        .param("page", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void renderOfAFullPoolIs503() throws Exception {
        int taken = 0;
        while (pool.tryAcquire()) {
            taken++;
        }
        try {
            render("PDF", TestCorpus.pdf(1), "1").andExpect(status().isServiceUnavailable());
        } finally {
            for (int i = 0; i < taken; i++) {
                pool.release();
            }
        }
    }
}
