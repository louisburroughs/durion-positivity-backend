package com.positivity.extractionworker;

import static com.positivity.extractionworker.WorkerApiTest.SECRET;
import static com.positivity.extractionworker.WorkerApiTest.TENANT;
import static com.positivity.extractionworker.WorkerApiTest.authorised;
import static com.positivity.extractionworker.WorkerApiTest.post;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.extractionworker.internal.security.TenantLabelFilter;
import com.positivity.extractionworker.internal.security.WorkerSecretFilter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AC 8 / ADR-0072: at DEBUG, across a success, a rejection, a refused secret and a type mismatch, nothing the
 * worker logs carries the file name, file content or the secret.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LogPolicyTest {

    private static final String FILE_NAME = "ACME-Corp-secret-invoice-2026.pdf";
    private static final String FILE_TEXT = "SUPER-SECRET-LINE-ITEM";

    @Autowired
    MockMvc mvc;

    private ListAppender<ILoggingEvent> appender;
    private Logger root;
    private Level previous;

    @BeforeEach
    void capture() {
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        previous = root.getLevel();
        root.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void release() {
        root.detachAppender(appender);
        root.setLevel(previous);
    }

    @Test
    void nothingSensitiveIsLogged() throws Exception {
        byte[] pdf = (new String(TestCorpus.pdf(2), StandardCharsets.ISO_8859_1)).getBytes(StandardCharsets.ISO_8859_1);
        byte[] withText = concat(pdf, FILE_TEXT.getBytes(StandardCharsets.ISO_8859_1));

        mvc.perform(authorised(post("/inspections", FILE_NAME, withText)).param("declaredType", "PDF"));
        mvc.perform(authorised(post("/inspections", FILE_NAME, TestCorpus.pdfWithJavaScript()))
                .param("declaredType", "PDF"));
        mvc.perform(authorised(post("/inspections", FILE_NAME, TestCorpus.image("png", 9, 9)))
                .param("declaredType", "PDF"));
        mvc.perform(authorised(post("/renderings", FILE_NAME, withText))
                .param("declaredType", "PDF")
                .param("page", "9"));
        mvc.perform(post("/inspections", FILE_NAME, withText)
                .header(WorkerSecretFilter.SECRET_HEADER, "guessed-" + SECRET)
                .header(TenantLabelFilter.TENANT_HEADER, TENANT)
                .param("declaredType", "PDF"));

        List<ILoggingEvent> events = List.copyOf(appender.list);
        assertThat(events).isNotEmpty();
        for (ILoggingEvent event : events) {
            String text = event.getFormattedMessage() + " " + event.getThrowableProxy() + " " + event.getMDCPropertyMap();
            assertThat(text)
                    .doesNotContain(FILE_NAME)
                    .doesNotContain("ACME")
                    .doesNotContain(FILE_TEXT)
                    .doesNotContain(SECRET)
                    .doesNotContain("guessed-");
        }
        assertThat(events.stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(message -> message.startsWith("inspect tenant=" + TENANT + " sha256="));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
