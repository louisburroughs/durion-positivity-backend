package com.positivity.extractionworker;

import static com.positivity.extractionworker.WorkerApiTest.authorised;
import static com.positivity.extractionworker.WorkerApiTest.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.extractionworker.internal.service.ScratchSpace;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AC 6 and AC 9 through the API: a child that overruns its deadline or its heap is killed, answers the
 * resource-limit reason, leaves nothing in scratch, and the next request on the same worker succeeds. The
 * child is the test entry point, which misbehaves according to marker text in the (PNG-magic) input.
 */
@SpringBootTest(
        properties = {
            "pos.extraction-worker.sandbox.child-main-class=com.positivity.extractionworker.internal.service.SandboxTestMain",
            "pos.extraction-worker.sandbox.child-heap=48m",
            "pos.extraction-worker.deadline.inspect=PT3S",
            "pos.extraction-worker.deadline.render=PT3S"
        })
@AutoConfigureMockMvc
class SandboxFaultApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ScratchSpace scratch;

    @AfterEach
    void scratchIsEmpty() throws IOException {
        try (Stream<Path> entries = Files.list(scratch.root())) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    private static byte[] pngMagicWith(String marker) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        out.writeBytes(marker.getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    @Test
    void deadlineBreachMemoryBreachAndThenAnOrdinaryRequestOnTheSameWorker() throws Exception {
        mvc.perform(authorised(post("/inspections", "a.png", pngMagicWith("SLEEP-PLEASE")))
                        .param("declaredType", "PNG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("REJECTED"))
                .andExpect(jsonPath("$.reasonCode").value("RESOURCE_LIMIT_EXCEEDED"));

        mvc.perform(authorised(post("/inspections", "b.png", pngMagicWith("OOM-PLEASE")))
                        .param("declaredType", "PNG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasonCode").value("RESOURCE_LIMIT_EXCEEDED"));

        mvc.perform(authorised(post("/inspections", "c.png", pngMagicWith("fine")))
                        .param("declaredType", "PNG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("ACCEPTED"))
                .andExpect(jsonPath("$.pageCount").value(7));
    }

    @Test
    void renderThatOverrunsIs422AndLeavesNothingBehind() throws Exception {
        mvc.perform(authorised(post("/renderings", "a.png", pngMagicWith("SLEEP-PLEASE")))
                        .param("declaredType", "PNG")
                        .param("page", "1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAGE_NOT_RENDERABLE"));
    }
}
