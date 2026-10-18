package com.positivity.extractionworker;

import static com.positivity.extractionworker.WorkerApiTest.authorised;
import static com.positivity.extractionworker.WorkerApiTest.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.extractionworker.internal.service.ScratchSpace;
import com.positivity.extractionworker.internal.service.WorkerSelfTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Ruling 2: when the worker's own machinery is broken (here the child's entry class does not exist) a file is
 * never rejected for it. The caller gets 503 WORKER_UNAVAILABLE, and the start-up self-test shows DOWN.
 */
@SpringBootTest(properties = "pos.extraction-worker.sandbox.child-main-class=no.such.Main")
@AutoConfigureMockMvc
class UnavailableSandboxApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ScratchSpace scratch;

    @Autowired
    WorkerSelfTest selfTest;

    @AfterEach
    void scratchIsEmpty() throws IOException {
        try (Stream<Path> entries = Files.list(scratch.root())) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    @Test
    void inspectAndRenderAnswer503InsteadOfRejectingTheFile() throws Exception {
        mvc.perform(authorised(post("/inspections", "a.pdf", TestCorpus.pdf(1))).param("declaredType", "PDF"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("WORKER_UNAVAILABLE"));
        mvc.perform(authorised(post("/renderings", "a.pdf", TestCorpus.pdf(1)))
                        .param("declaredType", "PDF")
                        .param("page", "1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WORKER_UNAVAILABLE"));
    }

    @Test
    void selfTestFlipsHealthDownWithoutFailingStartup() {
        selfTest.run();

        assertThat(selfTest.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(selfTest.health().getDetails()).containsEntry("sandbox", "failed");
    }
}
