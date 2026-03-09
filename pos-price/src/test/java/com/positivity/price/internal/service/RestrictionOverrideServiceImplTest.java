package com.positivity.price.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.price.dto.RestrictionOverrideRequest;
import com.positivity.price.enums.EvaluationContext;
import com.positivity.price.enums.LocationTag;
import com.positivity.price.enums.ServiceTag;
import com.positivity.price.internal.entity.RestrictionOverrideAudit;
import com.positivity.price.internal.entity.RestrictionRule;
import com.positivity.price.internal.repository.RestrictionOverrideAuditRepository;
import com.positivity.price.internal.repository.RestrictionRuleRepository;
import com.positivity.security.common.GatewaySecurityConstants;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link RestrictionOverrideServiceImpl} override issuance behavior.
 *
 * <p>Verifies that the service returns a populated {@code RestrictionOverrideResponse}
 * (non-null {@code overrideId} and {@code expiresAt}), that actor identity is sourced
 * from {@link SecurityContextHolder} per ADR-0018, and that an audit record is
 * persisted on every successful override.</p>
 *
 * Issue: #43
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RestrictionOverrideServiceImpl – override issuance and actor resolution")
class RestrictionOverrideServiceImplTest {

    @Mock
    private RestrictionOverrideAuditRepository auditRepository;

    @Mock
    private RestrictionRuleRepository ruleRepository;

    private RestrictionOverrideServiceImpl service;

    /**
     * Prepares a real authenticated security context with {@code "test-actor"} as
     * principal and the {@code pricing:restriction:override} authority.
     *
     * <p>Per ADR-0018 the service layer must read actor from
     * {@link SecurityContextHolder}, not from any request payload field.</p>
     */
    @BeforeEach
    void setUp() {
        service = new RestrictionOverrideServiceImpl(auditRepository, ruleRepository);
        when(auditRepository.save(any(RestrictionOverrideAudit.class))).thenAnswer(invocation -> {
            RestrictionOverrideAudit input = invocation.getArgument(0);
            if (input.getOverrideId() == null) {
                input.setOverrideId(UUID.randomUUID());
            }
            return input;
        });
        when(ruleRepository.findById(any(UUID.class))).thenReturn(Optional.of(someRule()));

        var auth = new UsernamePasswordAuthenticationToken(
                "test-actor",
                null,
                List.of(new SimpleGrantedAuthority("pricing:restriction:override")));
        auth.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "test-actor"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Successful override must return a response with a non-null overrideId and
     * a non-null expiresAt timestamp.
     */
    @Test
    @DisplayName("ROS-001: valid request → non-null overrideId and expiresAt")
    void givenAuthenticatedUser_whenCreateOverride_thenReturnsOverrideIdAndExpiry() {
        UUID ruleId = UUID.randomUUID();
        var request = new RestrictionOverrideRequest(
            ruleId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            EvaluationContext.CHECKOUT,
            "APPROVED_VENDOR",
            null,
            null);
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(someRule()));

        var response = service.createOverride(request);

        assertThat(response.overrideId()).isNotNull();
        assertThat(response.expiresAt()).isNotNull();
        verify(auditRepository).save(any(RestrictionOverrideAudit.class));
    }

    /**
     * The returned overrideId must be a valid (non-null) UUID instance.
     */
    @Test
    @DisplayName("ROS-002: overrideId is a valid UUID")
    void givenValidRequest_whenCreateOverride_thenOverrideIdIsUUID() {
        UUID ruleId = UUID.randomUUID();
        var request = new RestrictionOverrideRequest(
            ruleId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            EvaluationContext.QUOTE,
            "SEASONAL_EXCEPTION",
            null,
            null);
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(someRule()));

        var response = service.createOverride(request);

        assertThat(response.overrideId()).isInstanceOf(UUID.class);
    }

    /**
     * The service must resolve actor identity from the security context
     * (ADR-0018), not from anything in the request body.
     *
     * <p>The {@code RestrictionOverrideRequest} carries no actor field; any attempt
     * to introduce one would violate ADR-0018. This test confirms the operation
     * completes successfully with the context-provided actor ({@code "test-actor"}).
     * GREEN validation verifies that {@code "test-actor"} appears in the persisted
     * audit record.</p>
     */
    @Test
    @DisplayName("ROS-003: actor from SecurityContext, not request body (ADR-0018)")
    void givenActorFromContextNotRequestBody_whenCreateOverride_thenActorEqualsAuthenticatedUser() {
        UUID ruleId = UUID.randomUUID();
        var request = new RestrictionOverrideRequest(
            ruleId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            EvaluationContext.CHECKOUT,
            "APPROVED_VENDOR",
            null,
            null);
        when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(someRule()));

        var response = service.createOverride(request);

        // Response doesn't carry actor; GREEN phase verifies audit persistence carries "test-actor"
        assertThat(response).isNotNull();
        assertThat(response.overrideId()).isNotNull();
        ArgumentCaptor<RestrictionOverrideAudit> captor = ArgumentCaptor.forClass(RestrictionOverrideAudit.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().getActor()).isEqualTo("test-actor");
    }

    /**
     * An audit record must be written for every successful override creation.
     *
        * <p>Verifies that the audit repository {@code save()} is called on successful
        * override creation.</p>
     */
    @Test
    @DisplayName("ROS-004: audit record written on every successful override")
    void givenValidOverride_whenCreateOverride_thenAuditRecordIsWritten() {
        var request = new RestrictionOverrideRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                EvaluationContext.QUOTE,
                "MANAGER_AUTH",
                "seasonal exception",
                "manager.user");

        var response = service.createOverride(request);

        assertThat(response.overrideId()).isNotNull();
        verify(auditRepository).save(any(RestrictionOverrideAudit.class));
    }

    private RestrictionRule someRule() {
        return RestrictionRule.builder()
                .ruleId(UUID.randomUUID())
                .productId(UUID.randomUUID())
                .locationTag(LocationTag.RETAIL_STORE)
                .serviceTag(ServiceTag.WORKORDER)
                .effectiveFrom(LocalDate.now().minusDays(1))
                .effectiveTo(null)
                .active(true)
                .overrideable(true)
                .policyVersion(1)
                .build();
    }
}
