package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.dto.NltiRequestDTO;
import com.positivity.mcp.internal.dto.NltiResponseV1;
import com.positivity.mcp.internal.entity.NltiSession;
import com.positivity.mcp.internal.repository.NltiRequestRepository;
import com.positivity.mcp.internal.repository.NltiSessionRepository;
import com.positivity.mcp.service.NltiRequestService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for NLTI-001 — NltiRequestService session creation,
 * session reuse, and repository persistence behaviors.
 *
 * <p>
 * Tests exercise the real {@link NltiRequestServiceImpl} against an H2
 * in-memory database (application-test.yml). The implementation is GREEN:
 * all tests verify real behavior end-to-end.
 *
 * Issue: NLTI-001
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"pos.security.permission-registration.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
@Transactional
class NltiRequestServiceIT {

    // Hardcoded test session UUID — no UUID.randomUUID() per ADR
    private static final UUID FIXED_SESSION_ID = UUID.fromString("00000000-0000-7000-8000-000000000010");

    @Autowired
    private NltiRequestService nltiRequestService;

    @Autowired
    private NltiRequestRepository nltiRequestRepository;

    @Autowired
    private NltiSessionRepository nltiSessionRepository;

    // ─── AC-1: submit a valid prompt → 202 envelope returned ─────────────────

    /**
     * AC-1: Submitting a valid prompt must return a non-null response with
     * {@code requestId}, {@code correlationId}, {@code sessionId}, and
     * {@code status = "ACCEPTED"}.
     *
     * <p>
     * The service returns a non-null response with all required envelope fields.
     */
    @Test
    @DisplayName("AC-1: submit with valid prompt returns response with all required envelope fields")
    void submit_withValidPrompt_returnsResponseWithRequiredFields() {
        // Arrange — Issue NLTI-001: minimal request without pre-existing session
        NltiRequestDTO request = new NltiRequestDTO("check inventory levels", null, null);

        // Act
        NltiResponseV1 response = nltiRequestService.submit(request);

        // Assert desired behavior
        assertThat(response.requestId()).isNotNull();
        assertThat(response.correlationId()).isNotNull();
        assertThat(response.sessionId()).isNotNull();
        assertThat(response.status()).isEqualTo("ACCEPTED");
    }

    // ─── AC-5: same sessionId on two calls → single session record ───────────

    /**
     * AC-5: Repeated requests sharing the same {@code sessionId} must reuse the
     * existing session — no duplicate {@code NltiSession} row should be created.
     *
     * <p>
     * The service correctly finds and reuses the existing session.
     */
    @Test
    @DisplayName("AC-5: two submits with same sessionId reuse the existing session")
    void submit_withSameSessionId_reusesExistingSession() {
        // Arrange — Issue NLTI-001: seed an existing session owned by "system"
        // (the service fallback subject when no authentication context is present).
        NltiSession seededSession = new NltiSession();
        seededSession.setId(FIXED_SESSION_ID);
        seededSession.setSubjectId("system");
        nltiSessionRepository.saveAndFlush(seededSession);

        // Both requests carry the same existing session UUID.
        NltiRequestDTO first = new NltiRequestDTO("close workorder 123", FIXED_SESSION_ID, null);
        NltiRequestDTO second = new NltiRequestDTO("reopen workorder 123", FIXED_SESSION_ID, null);

        // Act
        NltiResponseV1 response1 = nltiRequestService.submit(first);
        NltiResponseV1 response2 = nltiRequestService.submit(second);

        // Assert: both responses share the same sessionId; only one session row exists
        assertThat(response1.sessionId()).isEqualTo(FIXED_SESSION_ID);
        assertThat(response2.sessionId()).isEqualTo(FIXED_SESSION_ID);
        assertThat(nltiSessionRepository.count()).isEqualTo(1L);
    }

    // ─── AC-1 (null sessionId): null sessionId → new session created ─────────

    /**
     * Submitting with a null {@code sessionId} must result in the service
     * generating a fresh session and returning it in the response.
     *
     * <p>
     * The service generates a fresh session and returns its id in the response.
     */
    @Test
    @DisplayName("submit with null sessionId auto-creates a new session")
    void submit_withNullSessionId_createsNewSession() {
        // Arrange — Issue NLTI-001: no client-supplied session
        NltiRequestDTO request = new NltiRequestDTO("list low stock items", null, null);

        // Act
        NltiResponseV1 response = nltiRequestService.submit(request);

        // Assert: a new session UUID has been generated by the service
        assertThat(response.sessionId()).isNotNull();
        assertThat(nltiSessionRepository.count()).isEqualTo(1L);
    }

    // ─── AC-1 (persistence): submitted request stored in repository ──────────

    /**
     * A successfully submitted request must be persisted in
     * {@link com.positivity.mcp.internal.repository.NltiRequestRepository} so
     * that it is linked to the returned {@code requestId}.
     *
     * <p>
     * The request is persisted and retrievable by its returned requestId.
     */
    @Test
    @DisplayName("submit stores the request record in NltiRequestRepository")
    void submit_storesRequestInRepository() {
        // Arrange — Issue NLTI-001: a request that should produce a persisted row
        NltiRequestDTO request = new NltiRequestDTO("run end-of-day report", null, null);

        // Act
        NltiResponseV1 response = nltiRequestService.submit(request);

        // Assert: exactly one NltiRequest row exists for the returned requestId
        assertThat(nltiRequestRepository.count()).isEqualTo(1L);
        assertThat(nltiRequestRepository.findById(response.requestId())).isPresent();
    }
}
