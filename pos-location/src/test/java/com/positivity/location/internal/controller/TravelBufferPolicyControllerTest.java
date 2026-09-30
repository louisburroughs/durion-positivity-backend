package com.positivity.location.internal.controller;

import static com.positivity.location.config.LocationScopeTestSupport.as;
import static com.positivity.location.config.LocationScopeTestSupport.clearCaller;
import static com.positivity.location.config.LocationScopeTestSupport.preRollout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.location.config.LocationScopeTestSupport;
import com.positivity.location.internal.entity.TravelBufferPolicyEntity;
import com.positivity.location.internal.repository.TravelBufferPolicyRepository;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.TravelBufferPolicyServiceImpl;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Write-path proof for the travel buffer policy endpoints (#2256): the request goes through the
 * real controller, bean validation, error advices and {@link TravelBufferPolicyServiceImpl}; only
 * the repository is mocked, so each refusal asserts the status and {@code code} the client sees and
 * that {@code saveAndFlush} was never reached.
 */
@WebMvcTest(TravelBufferPolicyController.class)
@Import({
    TravelBufferPolicyServiceImpl.class,
    LocationScopeAutoConfiguration.class,
    WebCommonErrorAutoConfiguration.class,
    LocationScopeTestSupport.SliceConfig.class
})
@ActiveProfiles("test")
class TravelBufferPolicyControllerTest {

    private static final String URL = "/v1/travel-buffer-policies";
    private static final String POLICY_URL = "/v1/travel-buffer-policies/{id}";
    private static final UUID POLICY_ID = UUID.fromString("019200bb-0000-7000-8000-000000000c01");
    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TravelBufferPolicyRepository repository;

    private TravelBufferPolicyEntity existing;

    @BeforeEach
    void setUp() {
        existing = TravelBufferPolicyEntity.builder()
                .id(POLICY_ID)
                .name("Standard Buffer")
                .bufferType("FIXED_MINUTES")
                .bufferValue(new BigDecimal("15"))
                .notes("old")
                .build();
        as(preRollout(LocationPermissions.TRAVEL_BUFFER_POLICY_MANAGE));
    }

    @AfterEach
    void tearDown() {
        clearCaller();
    }

    private void policyExists() {
        when(repository.findById(POLICY_ID)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any(TravelBufferPolicyEntity.class))).thenAnswer(call -> call.getArgument(0));
    }

    private void nothingPersisted() {
        verify(repository, never()).saveAndFlush(any(TravelBufferPolicyEntity.class));
        verify(repository, never()).save(any(TravelBufferPolicyEntity.class));
    }

    private void postRefused(String body, String field) throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(VALIDATION_ERROR))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem(field)));
    }

    private static DataIntegrityViolationException uniqueViolation(String constraint) {
        return new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("ERROR: duplicate key value violates unique constraint \"" + constraint + "\""));
    }

    // ------------------------------------------------------------------ create

    @Test
    @DisplayName("POST a valid policy answers 201 and flushes the insert")
    void createFlushes() throws Exception {
        when(repository.saveAndFlush(any(TravelBufferPolicyEntity.class))).thenAnswer(call -> call.getArgument(0));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" Metro \",\"bufferType\":\"FIXED_MINUTES\",\"bufferValue\":30}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Metro"));

        verify(repository).saveAndFlush(any(TravelBufferPolicyEntity.class));
    }

    @Test
    @DisplayName("POST a blank name answers 400 VALIDATION_ERROR on name before the service runs")
    void createBlankName() throws Exception {
        postRefused("{\"name\":\"  \",\"bufferType\":\"FIXED_MINUTES\",\"bufferValue\":30}", "name");

        nothingPersisted();
    }

    @Test
    @DisplayName("POST a missing name or bufferType answers 400 VALIDATION_ERROR")
    void createMissingRequired() throws Exception {
        postRefused("{\"bufferType\":\"FIXED_MINUTES\"}", "name");
        postRefused("{\"name\":\"Metro\"}", "bufferType");
        postRefused("{\"name\":\"Metro\",\"bufferType\":\"\"}", "bufferType");

        nothingPersisted();
    }

    @Test
    @DisplayName("POST a negative bufferValue answers 400 VALIDATION_ERROR on bufferValue")
    void createNegativeValue() throws Exception {
        postRefused("{\"name\":\"Metro\",\"bufferType\":\"FIXED_MINUTES\",\"bufferValue\":-1}", "bufferValue");

        nothingPersisted();
    }

    @Test
    @DisplayName("POST an unknown bufferType answers 400 VALIDATION_ERROR on bufferType")
    void createUnknownType() throws Exception {
        postRefused("{\"name\":\"Metro\",\"bufferType\":\"FLAT_MINUTES\",\"bufferValue\":5}", "bufferType");

        nothingPersisted();
    }

    @Test
    @DisplayName("POST a fractional FIXED_MINUTES bufferValue answers 400 VALIDATION_ERROR on bufferValue")
    void createFractionalFixedMinutes() throws Exception {
        postRefused("{\"name\":\"Metro\",\"bufferType\":\"FIXED_MINUTES\",\"bufferValue\":1.5}", "bufferValue");

        nothingPersisted();
    }

    @Test
    @DisplayName("POST a duplicate name answers 409 TRAVEL_BUFFER_POLICY_NAME_TAKEN")
    void createDuplicateName() throws Exception {
        when(repository.saveAndFlush(any(TravelBufferPolicyEntity.class)))
                .thenThrow(uniqueViolation("travel_buffer_policies_name_key"));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Metro\",\"bufferType\":\"FIXED_MINUTES\",\"bufferValue\":30}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRAVEL_BUFFER_POLICY_NAME_TAKEN"));
    }

    @Test
    @DisplayName("POST that trips a different constraint answers 409 TRAVEL_BUFFER_POLICY_CONFLICT")
    void createOtherConstraint() throws Exception {
        when(repository.saveAndFlush(any(TravelBufferPolicyEntity.class)))
                .thenThrow(uniqueViolation("travel_buffer_policies_pkey"));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Metro\",\"bufferType\":\"FIXED_MINUTES\",\"bufferValue\":30}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRAVEL_BUFFER_POLICY_CONFLICT"));
    }

    // ------------------------------------------------------------------- patch

    @Test
    @DisplayName("PATCH a null bufferType answers 400 on bufferType and changes nothing")
    void patchNullBufferType() throws Exception {
        policyExists();

        mockMvc.perform(patch(POLICY_URL, POLICY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bufferType\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(VALIDATION_ERROR))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("bufferType"));

        nothingPersisted();
        assertThat(existing.getBufferType()).isEqualTo("FIXED_MINUTES");
    }

    @Test
    @DisplayName("PATCH a non-numeric or negative bufferValue answers 400 on bufferValue")
    void patchBadBufferValue() throws Exception {
        policyExists();

        for (String body : new String[] {"{\"bufferValue\":\"lots\"}", "{\"bufferValue\":-5}"}) {
            mockMvc.perform(patch(POLICY_URL, POLICY_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(VALIDATION_ERROR))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("bufferValue"));
        }

        nothingPersisted();
    }

    @Test
    @DisplayName("PATCH cannot rename: a name key is ignored, the stored name stays")
    void patchIgnoresName() throws Exception {
        policyExists();

        mockMvc.perform(patch(POLICY_URL, POLICY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\",\"notes\":\"new notes\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Standard Buffer"))
                .andExpect(jsonPath("$.notes").value("new notes"));

        verify(repository).saveAndFlush(existing);
    }

    @Test
    @DisplayName("PATCH a malformed id answers 400 and an unknown id answers 404")
    void patchBadOrUnknownId() throws Exception {
        when(repository.findById(POLICY_ID)).thenReturn(Optional.empty());

        mockMvc.perform(patch(POLICY_URL, "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch(POLICY_URL, POLICY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"x\"}"))
                .andExpect(status().isNotFound());

        nothingPersisted();
    }
}
