package com.positivity.shopmanager.internal.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.web.common.GlobalApiExceptionHandler;
import com.positivity.web.common.ReplicationPendingException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * #1994: this module's advice must not swallow {@link ReplicationPendingException} before the
 * shared advice renders it as {@code 503} with {@code Retry-After}. Both advices are registered,
 * as the running service does.
 */
class ReplicationPendingResponseTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PendingController())
            .setControllerAdvice(new GlobalExceptionHandler(clock), new GlobalApiExceptionHandler(clock))
            .build();

    @Test
    void replicationPendingIsA503WithRetryAfterAndItsCode() throws Exception {
        mockMvc.perform(get("/pending"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("CRM_REPLICATION_PENDING"))
                .andExpect(jsonPath("$.status").value(503));
    }

    @RestController
    static class PendingController {
        @GetMapping("/pending")
        String pending() {
            throw new ReplicationPendingException(
                    "CRM_REPLICATION_PENDING", "not yet", UUID.fromString("01960003-0000-7000-8000-0000000000a1"));
        }
    }
}
