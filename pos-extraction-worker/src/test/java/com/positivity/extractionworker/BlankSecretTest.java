package com.positivity.extractionworker;

import static com.positivity.extractionworker.WorkerApiTest.TENANT;
import static com.positivity.extractionworker.WorkerApiTest.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.extractionworker.internal.security.TenantLabelFilter;
import com.positivity.extractionworker.internal.security.WorkerSecretFilter;
import com.positivity.extractionworker.internal.service.ScratchSpace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** AC 7: a worker with no configured secret refuses every request, even one that sends a header. */
@SpringBootTest(properties = "pos.extraction-worker.api-secret=")
@AutoConfigureMockMvc
class BlankSecretTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ScratchSpace scratch;

    @Test
    void everyRequestIs401SecretMissing() throws Exception {
        for (String header : new String[] {"anything", ""}) {
            mvc.perform(post("/inspections", "bill.pdf", TestCorpus.pdf(1))
                            .header(WorkerSecretFilter.SECRET_HEADER, header)
                            .header(TenantLabelFilter.TENANT_HEADER, TENANT)
                            .param("declaredType", "PDF"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("EXTRACTION_WORKER_SECRET_MISSING"));
        }
        try (Stream<Path> entries = Files.list(scratch.root())) {
            assertThat(entries.toList()).isEmpty();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
