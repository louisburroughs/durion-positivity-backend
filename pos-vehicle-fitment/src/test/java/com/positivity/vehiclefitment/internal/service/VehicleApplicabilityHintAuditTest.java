package com.positivity.vehiclefitment.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.events.EmitEvent;
import com.positivity.vehiclefitment.internal.controller.VehicleApplicabilityHintController;
import com.positivity.vehiclefitment.internal.dto.CreateHintRequest;
import com.positivity.vehiclefitment.internal.dto.FitmentTagDto;
import com.positivity.vehiclefitment.internal.dto.UpdateHintRequest;
import com.positivity.vehiclefitment.internal.entity.TagType;
import com.positivity.vehiclefitment.internal.entity.VehicleApplicabilityHint;
import com.positivity.vehiclefitment.internal.repository.VehicleApplicabilityHintRepository;
import java.lang.reflect.Method;
import java.util.Collections;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * ADR-0018 compliance and event annotation audit tests for
 * {@link VehicleApplicabilityHintServiceImpl} and
 * {@link VehicleApplicabilityHintController}.
 *
 * <p>
 * Verifies:
 * </p>
 * <ul>
 * <li>Actor fields ({@code createdBy}, {@code updatedBy}) are sourced from
 * the Spring SecurityContext, NOT from the request body (ADR-0018).</li>
 * <li>Controller methods carry the correct {@link EmitEvent} annotation IDs:
 * {@code VEHICLE_HINT_CREATED}, {@code VEHICLE_HINT_UPDATED},
 * {@code VEHICLE_HINT_DELETED} (story #44 AC1, AC2).</li>
 * </ul>
 *
 * Issue: #44
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VehicleApplicabilityHintService — ADR-0018 and @EmitEvent audit")
class VehicleApplicabilityHintAuditTest {

    private static final UUID TEST_HINT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TEST_PRODUCT_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock
    private VehicleApplicabilityHintRepository hintRepository;

    @InjectMocks
    private VehicleApplicabilityHintServiceImpl service;

    @BeforeEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void resetSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void setAuthenticatedUserInSecurityContext(String username) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(username, null, Collections.emptyList());
        authentication.setDetails(Map.of("username", username));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ADR-0018: createdBy must come from SecurityContext
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * ADR-0018: when {@code createHint} is called with an authenticated user,
     * the {@code createdBy} field on the persisted entity MUST be the username
     * from the SecurityContext — NOT the {@code createdBy} field from the request
     * body.
     *
     * <p>
     * Asserts that {@code createdBy} is sourced from Spring SecurityContext,
     * satisfying ADR-0018 actor resolution.
     * </p>
     *
     * Issue: #44 (ADR-0018 §createdBy)
     */
    @Test
    @DisplayName("ADR-0018 createHint: createdBy is from SecurityContext, not request body")
    void givenAuthenticatedUser_whenCreateHint_thenCreatedByIsFromSecurityContext() {
        // Arrange
        String securityContextUser = "test-admin";
        String requestBodyUser = "request-body-user";

        setAuthenticatedUserInSecurityContext(securityContextUser);

        CreateHintRequest request =
                new CreateHintRequest(TEST_PRODUCT_ID, List.of(new FitmentTagDto(TagType.MAKE, "Toyota")));

        VehicleApplicabilityHint savedHint = new VehicleApplicabilityHint();
        savedHint.setHintId(TEST_HINT_ID);
        savedHint.setProductId(TEST_PRODUCT_ID);
        when(hintRepository.save(any(VehicleApplicabilityHint.class))).thenReturn(savedHint);

        // Act
        service.createHint(request);

        // Assert: the entity passed to save() must have createdBy from SecurityContext
        ArgumentCaptor<VehicleApplicabilityHint> captor = ArgumentCaptor.forClass(VehicleApplicabilityHint.class);
        verify(hintRepository).save(captor.capture());

        assertThat(captor.getValue().getCreatedBy())
                .as(
                        "ADR-0018: createdBy MUST be sourced from SecurityContext ('%s'), not request body ('%s')",
                        securityContextUser, requestBodyUser)
                .isEqualTo(securityContextUser)
                .isNotEqualTo(requestBodyUser);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ADR-0018: updatedBy must come from SecurityContext
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * ADR-0018: when {@code updateHint} is called with an authenticated user,
     * the {@code updatedBy} field on the persisted entity MUST be the username
     * from the SecurityContext — NOT the {@code updatedBy} field from the request
     * body.
     *
     * <p>
     * Asserts that {@code updatedBy} is sourced from Spring SecurityContext,
     * satisfying ADR-0018 actor resolution.
     * </p>
     *
     * Issue: #44 (ADR-0018 §updatedBy)
     */
    @Test
    @DisplayName("ADR-0018 updateHint: updatedBy is from SecurityContext, not request body")
    void givenAuthenticatedUser_whenUpdateHint_thenUpdatedByIsFromSecurityContext() {
        // Arrange
        String securityContextUser = "test-admin";
        String requestBodyUser = "request-body-updater";

        setAuthenticatedUserInSecurityContext(securityContextUser);

        UpdateHintRequest updateRequest = new UpdateHintRequest(List.of(new FitmentTagDto(TagType.MODEL, "Corolla")));

        VehicleApplicabilityHint existing = new VehicleApplicabilityHint();
        existing.setHintId(TEST_HINT_ID);
        existing.setProductId(TEST_PRODUCT_ID);
        existing.setCreatedBy("original-creator");

        when(hintRepository.findById(TEST_HINT_ID)).thenReturn(Optional.of(existing));
        when(hintRepository.save(any(VehicleApplicabilityHint.class))).thenReturn(existing);

        // Act
        service.updateHint(TEST_HINT_ID, updateRequest);

        // Assert: the entity passed to save() must have updatedBy from SecurityContext
        ArgumentCaptor<VehicleApplicabilityHint> captor = ArgumentCaptor.forClass(VehicleApplicabilityHint.class);
        verify(hintRepository).save(captor.capture());

        assertThat(captor.getValue().getUpdatedBy())
                .as(
                        "ADR-0018: updatedBy MUST be sourced from SecurityContext ('%s'), not request body ('%s')",
                        securityContextUser, requestBodyUser)
                .isEqualTo(securityContextUser)
                .isNotEqualTo(requestBodyUser);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // @EmitEvent annotation presence and ID correctness
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * AC1 / Story #44: the {@code createHint} controller method MUST carry
     * {@code @EmitEvent(id = "VEHICLE_HINT_CREATED")}.
     *
     * <p>
     * Asserts that the controller emits the canonical create event ID
     * {@code VEHICLE_HINT_CREATED} required by Story #44 AC1.
     * </p>
     *
     * Issue: #44 (AC1 — VEHICLE_HINT_CREATED event)
     */
    @Test
    @DisplayName("AC1: createHint has @EmitEvent(id = \"VEHICLE_HINT_CREATED\")")
    void createController_hasEmitEventAnnotation_forCreate() throws Exception {
        Method createHint = VehicleApplicabilityHintController.class.getMethod(
                "createHint", com.positivity.vehiclefitment.internal.dto.CreateHintRequest.class);

        EmitEvent annotation = createHint.getAnnotation(EmitEvent.class);

        assertThat(annotation)
                .as("createHint() must be annotated with @EmitEvent")
                .isNotNull();
        assertThat(annotation.id())
                .as("@EmitEvent id must be 'VEHICLE_HINT_CREATED' (story #44 AC1)")
                .isEqualTo("VEHICLE_HINT_CREATED");
    }

    /**
     * AC2 / Story #44: the {@code updateHint} controller method MUST carry
     * {@code @EmitEvent(id = "VEHICLE_HINT_UPDATED")}.
     *
     * <p>
     * Asserts that the controller emits the canonical update event ID
     * {@code VEHICLE_HINT_UPDATED} required by Story #44 AC2.
     * </p>
     *
     * Issue: #44 (AC2 — VEHICLE_HINT_UPDATED event)
     */
    @Test
    @DisplayName("AC2: updateHint has @EmitEvent(id = \"VEHICLE_HINT_UPDATED\")")
    void updateController_hasEmitEventAnnotation_forUpdate() throws Exception {
        Method updateHint = VehicleApplicabilityHintController.class.getMethod(
                "updateHint", UUID.class, com.positivity.vehiclefitment.internal.dto.UpdateHintRequest.class);

        EmitEvent annotation = updateHint.getAnnotation(EmitEvent.class);

        assertThat(annotation)
                .as("updateHint() must be annotated with @EmitEvent")
                .isNotNull();
        assertThat(annotation.id())
                .as("@EmitEvent id must be 'VEHICLE_HINT_UPDATED' (story #44 AC2)")
                .isEqualTo("VEHICLE_HINT_UPDATED");
    }

    /**
     * Story #44: the {@code deleteHint} controller method MUST carry
     * {@code @EmitEvent(id = "VEHICLE_HINT_DELETED")}.
     *
     * <p>
     * Asserts that the controller emits the canonical delete event ID
     * {@code VEHICLE_HINT_DELETED} required by Story #44.
     * </p>
     *
     * Issue: #44 (delete event)
     */
    @Test
    @DisplayName("deleteHint has @EmitEvent(id = \"VEHICLE_HINT_DELETED\")")
    void deleteController_hasEmitEventAnnotation_forDelete() throws Exception {
        Method deleteHint = VehicleApplicabilityHintController.class.getMethod("deleteHint", UUID.class);

        EmitEvent annotation = deleteHint.getAnnotation(EmitEvent.class);

        assertThat(annotation)
                .as("deleteHint() must be annotated with @EmitEvent")
                .isNotNull();
        assertThat(annotation.id())
                .as("@EmitEvent id must be 'VEHICLE_HINT_DELETED' (story #44 delete event)")
                .isEqualTo("VEHICLE_HINT_DELETED");
    }
}
